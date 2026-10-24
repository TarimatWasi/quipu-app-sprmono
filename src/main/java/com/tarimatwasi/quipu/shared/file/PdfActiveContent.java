package com.tarimatwasi.quipu.shared.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jspecify.annotations.Nullable;

/**
 * Looks for the entries that make a PDF run something or carry something: JavaScript, a launch or
 * another action that leaves the document, additional actions, an action on open that is not a
 * plain go-to, a form that submits or imports data, an XFA form, rich media, an embedded file or a
 * file attachment. A voucher, a document of the onboarding or a contract is uploaded by one person
 * and opened by another, so it must be a document and not a program (D-52).
 *
 * <p>The file is parsed with PDFBox in strict mode, from memory only, and every object that the
 * cross-reference table lists is read, wherever it lives: in the body, inside a compressed object
 * stream, or not referenced from the catalog at all. Every object stream is read whole, so an
 * object it holds that the table does not list is read too. The parser decodes the names, so {@code
 * /J#61vaScript} is {@code /JavaScript}. A link to an address ({@code /URI}) is a document feature
 * and is accepted.
 *
 * <p>It <strong>fails closed</strong>: a file that does not parse, that lists an object the parser
 * cannot read, or that needs a password to open is refused. A file with only an owner password (a
 * bank voucher, typically) opens with the empty password and is inspected as any other.
 *
 * <p>The memory is bounded. Content streams and images are never decoded. The streams that the
 * parser must decode, the cross-reference streams and the object streams, may only use Flate and
 * may inflate to at most {@link #MAX_INFLATED} bytes together; they are measured before PDFBox
 * decodes them. The streams that the parser caches (decrypted ones) live in memory up to a limit.
 */
final class PdfActiveContent {

  /** The most bytes the cross-reference and object streams of one file may inflate to. */
  static final long MAX_INFLATED = 32L * 1024 * 1024;

  /** The most memory the parser may use to cache streams, which it does when it decrypts them. */
  private static final long MAX_CACHE = 32L * 1024 * 1024;

  private static final COSName S = name("S");
  private static final COSName TYPE = name("Type");
  private static final COSName SUBTYPE = name("Subtype");
  private static final COSName FILTER = name("Filter");
  private static final COSName OPEN_ACTION = name("OpenAction");
  private static final COSName GO_TO = name("GoTo");
  private static final COSName OBJECT_STREAM = name("ObjStm");
  private static final Set<COSName> FLATE = Set.of(name("FlateDecode"), name("Fl"));

  /** Keys that are active whatever their value. */
  private static final List<COSName> ACTIVE_KEYS =
      List.of(
          name("JS"),
          name("JavaScript"),
          name("EF"),
          name("EmbeddedFiles"),
          name("XFA"),
          name("RichMedia"),
          name("AA"));

  /** Action types ({@code /S}) that run something or leave the document. */
  private static final Set<COSName> ACTIVE_ACTIONS =
      Set.of(
          name("JavaScript"),
          name("Launch"),
          name("ImportData"),
          name("SubmitForm"),
          name("GoToE"),
          name("GoToR"),
          name("RichMediaExecute"),
          name("Rendition"));

  /** Values of {@code /Type} or {@code /Subtype} that carry a file or rich media. */
  private static final Set<COSName> ACTIVE_TYPES =
      Set.of(name("EmbeddedFile"), name("FileAttachment"), name("RichMedia"));

  private PdfActiveContent() {}

  private static COSName name(String name) {
    return COSName.getPDFName(name);
  }

  /** Whether the file has an active entry, or cannot be read well enough to tell. */
  static boolean found(byte[] content) {
    try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(content)) {
      BoundedParser parser = new BoundedParser(source);
      try (PDDocument document = parser.parse(false)) {
        return hasActiveObject(parser, document.getDocument()) || parser.refused;
      }
    } catch (IOException
        | IllegalArgumentException
        | IllegalStateException
        | ClassCastException
        | IndexOutOfBoundsException
        | NullPointerException
        | ArithmeticException
        | UnsupportedOperationException e) {
      // Not a PDF that parses, a wrong password, or over a limit: refused, without logging it.
      // The unchecked ones are how PDFBox fails on some malformed files.
      return true;
    }
  }

  private static boolean hasActiveObject(BoundedParser parser, COSDocument document)
      throws IOException {
    Deque<COSBase> pending = new ArrayDeque<>();
    for (COSObjectKey key : List.copyOf(document.getXrefTable().keySet())) {
      COSBase object = document.getObjectFromPool(key).getObject();
      if (object == null || parser.refused) {
        return true;
      }
      pending.push(object);
      if (object instanceof COSStream stream && OBJECT_STREAM.equals(stream.getCOSName(TYPE))) {
        pending.addAll(parser.objectStream(key.getNumber()).values());
      }
    }
    // References are not followed: every indirect object is in the loop above.
    while (!pending.isEmpty()) {
      COSBase object = pending.pop();
      if (object instanceof COSDictionary dictionary) {
        if (isActive(dictionary)) {
          return true;
        }
        pending.addAll(dictionary.getValues());
      } else if (object instanceof COSArray array) {
        array.forEach(pending::push);
      }
    }
    return false;
  }

  private static boolean isActive(COSDictionary dictionary) {
    for (COSName key : ACTIVE_KEYS) {
      if (dictionary.containsKey(key)) {
        return true;
      }
    }
    if (in(ACTIVE_ACTIONS, dictionary.getCOSName(S))
        || in(ACTIVE_TYPES, dictionary.getCOSName(TYPE))
        || in(ACTIVE_TYPES, dictionary.getCOSName(SUBTYPE))) {
      return true;
    }
    COSBase open = dictionary.getDictionaryObject(OPEN_ACTION);
    return open != null && !(open instanceof COSArray) && !isGoTo(open);
  }

  private static boolean isGoTo(COSBase action) {
    return action instanceof COSDictionary dictionary && GO_TO.equals(dictionary.getCOSName(S));
  }

  private static boolean in(Set<COSName> names, @Nullable COSName name) {
    return name != null && names.contains(name);
  }

  /**
   * A strict parser that measures every stream it has to decode before PDFBox decodes it, and reads
   * each object stream once.
   */
  private static final class BoundedParser extends PDFParser {

    private final Map<Long, Map<COSObjectKey, COSBase>> objectStreams = new HashMap<>();
    private long inflated;
    private boolean readingTrailer;
    private boolean refused;

    BoundedParser(RandomAccessRead source) throws IOException {
      super(source, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_CACHE).streamCache);
    }

    @Override
    protected COSDictionary retrieveTrailer() throws IOException {
      readingTrailer = true;
      try {
        return super.retrieveTrailer();
      } finally {
        readingTrailer = false;
      }
    }

    /** While the trailer is read, the streams parsed are cross-reference streams, decoded next. */
    @Override
    protected COSStream parseCOSStream(COSDictionary dictionary) throws IOException {
      COSStream stream = super.parseCOSStream(dictionary);
      if (readingTrailer) {
        measure(stream);
      }
      return stream;
    }

    /** Replaces the lookup of PDFBox, which decodes the object stream again on every miss. */
    @Override
    protected @Nullable COSBase parseObjectStreamObject(long objectStream, COSObjectKey key)
        throws IOException {
      return objectStream(objectStream).get(key);
    }

    Map<COSObjectKey, COSBase> objectStream(long number) throws IOException {
      Map<COSObjectKey, COSBase> objects = objectStreams.get(number);
      if (objects == null) {
        objects = Map.of();
        objectStreams.put(number, objects); // a stream that holds itself finds it empty
        try {
          COSBase stream = document.getObjectFromPool(new COSObjectKey(number, 0)).getObject();
          if (!(stream instanceof COSStream objectStream)) {
            throw new IOException("Not an object stream");
          }
          measure(objectStream);
          objects = new PDFObjectStreamParser(objectStream, document).parseAllObjects();
          objectStreams.put(number, objects);
        } catch (IOException e) {
          refused = true; // PDFBox logs and swallows an IOException inside a lookup
          throw e;
        }
      }
      return objects;
    }

    /** Inflates the stream as PDFBox would, counting, and refuses it past the limit. */
    private void measure(COSStream stream) throws IOException {
      COSBase filter = stream.getDictionaryObject(FILTER);
      if (filter instanceof COSArray array && array.size() == 1) {
        filter = array.getObject(0);
      }
      if (filter == null) {
        return; // stored as is: no larger than the file
      }
      if (!FLATE.contains(filter)) {
        refuse("A stream the parser decodes must use Flate");
      }
      Inflater inflater = new Inflater(true);
      try (InputStream raw = stream.createRawInputStream()) {
        // PDFBox skips the two bytes of the zlib header and inflates raw deflate data.
        raw.skipNBytes(Math.min(2, stream.getLength()));
        byte[] input = new byte[64 * 1024];
        byte[] output = new byte[64 * 1024];
        while (!inflater.finished()) {
          if (inflater.needsInput()) {
            int read = raw.read(input);
            if (read < 0) {
              return;
            }
            inflater.setInput(input, 0, read);
          }
          int produced = inflater.inflate(output);
          if (produced == 0 && !inflater.needsInput()) {
            return; // wants a dictionary or is stuck: PDFBox would stop here too
          }
          inflated += produced;
          if (inflated > MAX_INFLATED) {
            refuse("Inflates beyond the limit");
          }
        }
      } catch (DataFormatException e) {
        // PDFBox keeps what inflated before the error; that is already counted.
      } finally {
        inflater.end();
      }
    }

    private void refuse(String reason) throws IOException {
      refused = true;
      throw new IOException(reason);
    }
  }
}
