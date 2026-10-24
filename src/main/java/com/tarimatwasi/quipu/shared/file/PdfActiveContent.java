package com.tarimatwasi.quipu.shared.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
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
 * Looks for the entries that make a PDF run something or carry something: JavaScript, an action
 * that is not a go-to or a link, additional actions, an action on open that is not a plain go-to, a
 * form that submits or imports data, an XFA form, rich media, 3D, sound or movies, an embedded file
 * or a file attachment. A voucher, a document of the onboarding or a contract is uploaded by one
 * person and opened by another, so it must be a document and not a program (D-52).
 *
 * <p>The file is parsed with PDFBox in strict mode, from memory only, and every object that the
 * cross-reference table lists is read, wherever it lives: in the body, inside a compressed object
 * stream, or not referenced from the catalog at all; so is the trailer. Every object stream is read
 * whole, so an object it holds that the table does not list is read too. The parser decodes the
 * names, so {@code /J#61vaScript} is {@code /JavaScript}. The keys of a map of named resources
 * ({@code /Resources /Font << /JS 5 0 R >>}) are names the producer chose, not entries: they are
 * not taken for active keys, and what they point to is inspected like anything else.
 *
 * <p>It <strong>fails closed</strong>: a file that does not parse, that lists an object the parser
 * cannot read, that needs a password to open or that is over a limit is refused, and so is any
 * failure of the parser. A file with only an owner password (a bank voucher, typically) opens with
 * the empty password and is inspected as any other.
 *
 * <p>The memory is bounded: content streams and images are never decoded; the streams that the
 * parser must decode (cross-reference and object streams) may only use Flate, with at most a PNG
 * predictor of {@value #MAX_COLUMNS} columns, and inflate to at most {@link #MAX_INFLATED} bytes
 * together, measured before PDFBox decodes them; the objects and the values parsed are counted; the
 * cache of decrypted streams lives in memory up to a limit; and one file is inspected at a time.
 * PDFBox keeps every name it parses in a global table, which is emptied after each file.
 */
final class PdfActiveContent {

  /** The most bytes the cross-reference and object streams of one file may inflate to. */
  static final long MAX_INFLATED = 8L * 1024 * 1024;

  /** The most objects a file may list, and the most its object streams may hold together. */
  static final int MAX_OBJECTS = 20_000;

  /** The most values (numbers, names, strings, arrays, dictionaries...) parsed in one file. */
  static final int MAX_VALUES = 250_000;

  /** The most columns of the PNG predictor of a stream the parser decodes. */
  static final int MAX_COLUMNS = 64;

  /** The largest file inspected; every upload route has a smaller or equal limit. */
  private static final int MAX_FILE = 10 * 1024 * 1024;

  /** The most memory the parser may use to cache streams, which it does when it decrypts them. */
  private static final long MAX_CACHE = 12L * 1024 * 1024;

  /** How long a file waits for the inspection of another one before it is refused. */
  private static final long WAIT_SECONDS = 3;

  /** One file at a time: an inspection may take tens of megabytes of a small heap. */
  static final Semaphore INSPECTION = new Semaphore(1, true);

  /**
   * Verdicts of files inspected ahead (see {@link #inspectAhead}), by the identity of the array of
   * bytes, which arrays use for {@code equals}; an entry goes with its array.
   */
  private static final Map<Object, Boolean> AHEAD =
      Collections.synchronizedMap(new WeakHashMap<>());

  private static final COSName S = name("S");
  private static final COSName TYPE = name("Type");
  private static final COSName SUBTYPE = name("Subtype");
  private static final COSName FILTER = name("Filter");
  private static final COSName DECODE_PARMS = name("DecodeParms");
  private static final COSName DECODE_PARMS_SHORT = name("DP");
  private static final COSName OPEN_ACTION = name("OpenAction");
  private static final COSName GO_TO = name("GoTo");
  private static final COSName ACTION = name("Action");
  private static final COSName NEXT = name("Next");
  private static final COSName OBJECT_STREAM = name("ObjStm");
  private static final COSName ROOT = name("Root");
  private static final COSName SIZE = name("Size");
  private static final COSName INDEX = name("Index");
  private static final COSName OBJECT_COUNT = name("N");
  private static final COSName RESOURCES = name("Resources");
  private static final Set<COSName> FLATE = Set.of(name("FlateDecode"), name("Fl"));

  /** The entries of a {@code /Resources} dictionary that map names to resources. */
  private static final Set<COSName> RESOURCE_KINDS =
      Set.of(
          name("Font"),
          name("XObject"),
          name("ExtGState"),
          name("ColorSpace"),
          name("Pattern"),
          name("Shading"),
          name("Properties"));

  /** Keys that are active whatever their value. */
  private static final List<COSName> ACTIVE_KEYS =
      List.of(
          name("JS"),
          name("JavaScript"),
          name("EF"),
          name("EmbeddedFiles"),
          name("XFA"),
          name("RichMedia"),
          name("AA"),
          name("OnInstantiate"));

  /** Action types ({@code /S}) refused in any dictionary. */
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

  /** The only action types an action may have: go to a place of the document, or open a link. */
  private static final Set<COSName> ALLOWED_ACTIONS = Set.of(GO_TO, name("URI"));

  /** The keys whose value is an action. */
  private static final Set<COSName> ACTION_KEYS = Set.of(name("A"), NEXT, OPEN_ACTION);

  /** Values of {@code /Type} or {@code /Subtype} that carry a file, media or 3D. */
  private static final Set<COSName> ACTIVE_TYPES =
      Set.of(
          name("EmbeddedFile"),
          name("FileAttachment"),
          name("RichMedia"),
          name("3D"),
          name("Movie"),
          name("Sound"),
          name("Screen"));

  private PdfActiveContent() {}

  private static COSName name(String name) {
    return COSName.getPDFName(name);
  }

  /**
   * Inspects the file now, before the caller opens a transaction or locks a row (TAR-39): {@link
   * #found} on the same array then gives this verdict without parsing it again.
   */
  static void inspectAhead(byte[] content) {
    AHEAD.put(content, inspect(content));
  }

  /** Whether the file has an active entry, or cannot be read well enough to tell. */
  static boolean found(byte[] content) {
    Boolean ahead = AHEAD.get(content);
    return ahead != null ? ahead : inspect(content);
  }

  private static boolean inspect(byte[] content) {
    if (content.length > MAX_FILE) {
      return true;
    }
    try {
      if (!INSPECTION.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
        return true; // busy: refused with the error of the route, as an invalid file
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
    try {
      return parseAndInspect(content);
    } finally {
      forgetNames();
      INSPECTION.release();
    }
  }

  private static boolean parseAndInspect(byte[] content) {
    try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(content)) {
      BoundedParser parser = new BoundedParser(source);
      try (PDDocument document = parser.parse(false)) {
        return hasActiveObject(parser, document.getDocument()) || parser.refused;
      }
    } catch (IOException
        | UncheckedIOException
        | IllegalArgumentException
        | IllegalStateException
        | ClassCastException
        | IndexOutOfBoundsException
        | NegativeArraySizeException
        | NoSuchElementException
        | NullPointerException
        | ArithmeticException
        | UnsupportedOperationException
        | StackOverflowError e) {
      // The inspection boundary: whatever a malformed file makes the parser throw refuses it, and
      // nothing of the file is logged.
      return true;
    }
  }

  /**
   * PDFBox 3.0.8 keeps every name it parses in a static table that nothing empties, so the names of
   * every file would stay in the heap. Names are compared by their bytes, so emptying the table
   * only costs the names being created again.
   */
  @SuppressWarnings("deprecation") // the only way to empty the table of PDFBox
  private static void forgetNames() {
    COSName.clearResources();
  }

  private static boolean hasActiveObject(BoundedParser parser, COSDocument document)
      throws IOException {
    COSDictionary trailer = document.getTrailer();
    Map<COSObjectKey, Long> table = document.getXrefTable();
    if (trailer == null
        || !(trailer.getItem(ROOT) instanceof COSObject)
        || table.size() > MAX_OBJECTS
        || trailer.getLong(SIZE) > MAX_OBJECTS) {
      return true;
    }
    Deque<COSBase> pending = new ArrayDeque<>();
    pending.push(trailer);
    for (COSObjectKey key : List.copyOf(table.keySet())) {
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
    List<COSDictionary> dictionaries = new ArrayList<>();
    int visited = 0;
    while (!pending.isEmpty()) {
      if (++visited > 2 * MAX_VALUES) {
        return true;
      }
      COSBase object = pending.pop();
      if (object instanceof COSDictionary dictionary) {
        dictionaries.add(dictionary);
        pending.addAll(dictionary.getValues());
      } else if (object instanceof COSArray array) {
        array.forEach(pending::push);
      }
    }
    Roles roles = new Roles(dictionaries);
    for (COSDictionary dictionary : dictionaries) {
      if (isActive(dictionary, roles)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isActive(COSDictionary dictionary, Roles roles) {
    if (in(ACTIVE_ACTIONS, dictionary.getCOSName(S))
        || in(ACTIVE_TYPES, dictionary.getCOSName(TYPE))
        || in(ACTIVE_TYPES, dictionary.getCOSName(SUBTYPE))) {
      return true;
    }
    boolean action =
        ACTION.equals(dictionary.getCOSName(TYPE))
            || (roles.actions.contains(dictionary) && dictionary.containsKey(S));
    if (action && !in(ALLOWED_ACTIONS, dictionary.getCOSName(S))) {
      return true;
    }
    if (roles.isResourceMap(dictionary)) {
      return false; // its keys are names of resources; what they point to is inspected apart
    }
    for (COSName key : ACTIVE_KEYS) {
      if (dictionary.containsKey(key)) {
        return true;
      }
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
   * How each dictionary is reached, by the key that points to it. A map of named resources is the
   * value of {@code /Font}, {@code /XObject}... in a {@code /Resources} dictionary (of a page, a
   * page tree node, a form XObject, a Type 3 font, a pattern); it counts as one only when nothing
   * reaches it by any other key, it says nothing of what it is ({@code /Type}, {@code /Subtype} or
   * {@code /S} with a name) and it is not a stream. An action is the value of {@code /A}, {@code
   * /Next} or {@code /OpenAction}.
   */
  private static final class Roles {

    private final Set<COSDictionary> resources = identitySet();
    private final Set<COSDictionary> asResourceMap = identitySet();
    private final Set<COSDictionary> otherwise = identitySet();
    private final Set<COSDictionary> actions = identitySet();

    Roles(List<COSDictionary> dictionaries) {
      for (COSDictionary dictionary : dictionaries) {
        if (dictionary.getDictionaryObject(RESOURCES) instanceof COSDictionary found) {
          resources.add(found);
        }
      }
      for (COSDictionary dictionary : dictionaries) {
        boolean inResources = resources.contains(dictionary);
        for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
          COSName key = entry.getKey();
          List<COSDictionary> targets = new ArrayList<>();
          collect(entry.getValue(), targets, 0);
          boolean map = inResources && RESOURCE_KINDS.contains(key);
          (map ? asResourceMap : otherwise).addAll(targets);
          if (ACTION_KEYS.contains(key)) {
            COSBase value = resolve(entry.getValue());
            if (value instanceof COSDictionary single) {
              actions.add(single);
            } else if (value instanceof COSArray && NEXT.equals(key)) {
              actions.addAll(targets); // a chain of actions
            }
          }
        }
      }
    }

    boolean isResourceMap(COSDictionary dictionary) {
      return asResourceMap.contains(dictionary)
          && !otherwise.contains(dictionary)
          && !(dictionary instanceof COSStream)
          && dictionary.getCOSName(TYPE) == null
          && dictionary.getCOSName(SUBTYPE) == null
          && dictionary.getCOSName(S) == null;
    }

    /** The dictionaries a value is or points to, inside arrays too. */
    private static void collect(@Nullable COSBase value, List<COSDictionary> targets, int depth) {
      COSBase resolved = resolve(value);
      if (resolved instanceof COSDictionary dictionary) {
        targets.add(dictionary);
      } else if (resolved instanceof COSArray array && depth < 8 && !(value instanceof COSObject)) {
        // a direct array: an indirect one is an object of its own, reached from the table
        array.forEach(element -> collect(element, targets, depth + 1));
      }
    }

    private static @Nullable COSBase resolve(@Nullable COSBase value) {
      return value instanceof COSObject reference ? reference.getObject() : value;
    }

    private static Set<COSDictionary> identitySet() {
      return Collections.newSetFromMap(new IdentityHashMap<>());
    }
  }

  /**
   * A strict parser that counts what it parses and measures every stream it has to decode before
   * PDFBox decodes it, and reads each object stream once.
   */
  private static final class BoundedParser extends PDFParser {

    private final Map<Long, Map<COSObjectKey, COSBase>> objectStreams = new HashMap<>();
    private long inflated;
    private int values;
    private int compressedObjects;
    private boolean readingTrailer;
    private boolean refused;

    BoundedParser(RandomAccessRead source) throws IOException {
      super(source, "", null, null, MemoryUsageSetting.setupMainMemoryOnly(MAX_CACHE).streamCache);
    }

    @Override
    protected COSBase parseDirObject() throws IOException {
      count();
      return super.parseDirObject();
    }

    @Override
    protected COSName parseCOSName() throws IOException {
      count();
      return super.parseCOSName();
    }

    void count() throws IOException {
      if (++values > MAX_VALUES) {
        refuse("Too many values");
      }
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
        limitEntries(stream);
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
          compressedObjects += Math.max(0, objectStream.getInt(OBJECT_COUNT));
          if (compressedObjects > MAX_OBJECTS) {
            refuse("Too many compressed objects");
          }
          measure(objectStream);
          objects = new CountingObjectStreamParser(objectStream, document, this).parseAllObjects();
          objectStreams.put(number, objects);
        } catch (IOException e) {
          refused = true; // PDFBox logs and swallows an IOException inside a lookup
          throw e;
        }
      }
      return objects;
    }

    /** A cross-reference stream may not list more than {@link #MAX_OBJECTS} entries. */
    private void limitEntries(COSStream stream) throws IOException {
      long entries = stream.getLong(SIZE);
      if (stream.getDictionaryObject(INDEX) instanceof COSArray index) {
        entries = 0;
        for (int i = 1; i < index.size(); i += 2) {
          entries += Math.max(0, index.getInt(i));
        }
      }
      if (entries > MAX_OBJECTS) {
        refuse("Too many entries");
      }
    }

    /**
     * Inflates the stream as PDFBox would, counting, and refuses it past the limit. PDFBox stops at
     * the end of the first deflate stream, at an error or at the end of the data, and so does this.
     */
    private void measure(COSStream stream) throws IOException {
      // PDFBox decodes by /Filter and reads the parameters from /DP or else /DecodeParms.
      COSBase filter = single(stream, FILTER);
      COSBase parameters = single(stream, DECODE_PARMS_SHORT);
      COSBase longParameters = single(stream, DECODE_PARMS);
      if (filter == null) {
        if (parameters != null || longParameters != null) {
          refuse("Decode parameters without a filter");
        }
        return; // stored as is: no larger than the file
      }
      if (!FLATE.contains(filter)) {
        refuse("A stream the parser decodes must use Flate");
      }
      if ((parameters != null && !isSmallPngPredictor(parameters))
          || (longParameters != null && !isSmallPngPredictor(longParameters))) {
        refuse("Unexpected decode parameters");
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

    /** The value of the key, or the only element of an array of one. */
    private static @Nullable COSBase single(COSStream stream, COSName key) throws IOException {
      COSBase value = stream.getDictionaryObject(key);
      if (value instanceof COSArray array) {
        if (array.size() != 1) {
          throw new IOException("More than one filter");
        }
        value = array.getObject(0);
      }
      return value == null || value instanceof COSNull ? null : value;
    }

    /** Predictor 10 to 15 (PNG), one colour of 8 bits, and few columns: the output shrinks. */
    private static boolean isSmallPngPredictor(COSBase parameters) {
      if (!(parameters instanceof COSDictionary dictionary)) {
        return false;
      }
      int predictor = dictionary.getInt(name("Predictor"), 1);
      int columns = dictionary.getInt(name("Columns"), 1);
      return predictor >= 10
          && predictor <= 15
          && columns >= 1
          && columns <= MAX_COLUMNS
          && dictionary.getInt(name("Colors"), 1) == 1
          && dictionary.getInt(name("BitsPerComponent"), 8) == 8;
    }

    private void refuse(String reason) throws IOException {
      refused = true;
      throw new IOException(reason);
    }
  }

  /** Parses an object stream counting its values against the budget of the file. */
  private static final class CountingObjectStreamParser extends PDFObjectStreamParser {

    private final BoundedParser budget;

    CountingObjectStreamParser(COSStream stream, COSDocument document, BoundedParser budget)
        throws IOException {
      super(stream, document);
      this.budget = budget;
    }

    @Override
    protected COSBase parseDirObject() throws IOException {
      budget.count();
      return super.parseDirObject();
    }

    @Override
    protected COSName parseCOSName() throws IOException {
      budget.count();
      return super.parseCOSName();
    }
  }
}
