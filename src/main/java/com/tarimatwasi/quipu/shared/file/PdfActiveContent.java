package com.tarimatwasi.quipu.shared.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.HexFormat;
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
 * Looks for the entries that make a PDF run something or carry something (see {@link
 * ActiveEntries}). A voucher, a document of the onboarding or a contract is uploaded by one person
 * and opened by another, so it must be a document and not a program (D-52).
 *
 * <p>The file is parsed with PDFBox in strict mode, from memory only. Every value reachable from
 * the trailer, from every object the cross-reference table lists and from every object an object
 * stream holds is visited once, whatever the key, the array or the reference that leads to it, and
 * every dictionary among them is judged by {@link ActiveEntries#isActive} on its own content. The
 * parser decodes the names, so {@code /J#61vaScript} is {@code /JavaScript}.
 *
 * <p>It <strong>fails closed</strong>: a file that does not parse, that lists an object the parser
 * cannot read, that has a direct {@code /Root}, that needs a password to open or that is over a
 * limit is refused, and so is any failure of the parser. A file with only an owner password (a bank
 * voucher, typically) opens with the empty password and is inspected as any other.
 *
 * <p>The memory is bounded: content streams and images are never decoded; the streams that the
 * parser must decode (cross-reference and object streams) may only use Flate, with at most a PNG
 * predictor of {@value #MAX_COLUMNS} columns, and inflate to at most {@link #MAX_INFLATED} bytes
 * together, measured before PDFBox decodes them; the objects (also the lines of a classic table,
 * before they are kept) and the values parsed are counted; the cache of decrypted streams lives in
 * memory up to a limit. PDFBox keeps every name it parses in a global table, which is emptied after
 * each file.
 *
 * <p><strong>Invariant</strong>: PDFBox is used only by this class and {@link ActiveEntries} (an
 * ArchUnit rule says so) and only under {@link #INSPECTION}, one file at a time; emptying the
 * global table of names relies on it. A file that waits too long for its turn is not judged and its
 * verdict is not kept: {@link FileInspectionBusyException}. The callers check the limit of their
 * route (5 MB, 10 MB for a contract) before they read the bytes, so a larger file never gets here.
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

  /** The most references followed one after another before a value is reached. */
  static final int MAX_HOPS = 32;

  /**
   * How long one inspection may take before the file is refused, not kept; tests lower it. A file
   * that is slow to parse is no verdict on the file but must not keep the others waiting.
   */
  static volatile long deadlineMillis = 2_000;

  /** How long a file waits for the inspection of another one before it is turned away. */
  static final long WAIT_SECONDS = 3;

  /** One file at a time: an inspection may take tens of megabytes of a small heap. */
  static final Semaphore INSPECTION = new Semaphore(1, true);

  /**
   * Verdicts of files inspected ahead (see {@link #inspectAhead}), by the identity of the array of
   * bytes, which arrays use for {@code equals}; an entry goes with its array. Only verdicts are
   * kept: a file turned away while busy or past the deadline is not. Invariant: nobody changes the
   * array between {@link #inspectAhead} and {@link #found}; the digest kept with the verdict checks
   * it, and a changed array is inspected again.
   */
  private static final Map<Object, Ahead> AHEAD = Collections.synchronizedMap(new WeakHashMap<>());

  private record Ahead(boolean active, String digest) {}

  private static final COSName TYPE = name("Type");
  private static final COSName FILTER = name("Filter");
  private static final COSName DECODE_PARMS = name("DecodeParms");
  private static final COSName DECODE_PARMS_SHORT = name("DP");
  private static final COSName OBJECT_STREAM = name("ObjStm");
  private static final COSName ROOT = name("Root");
  private static final COSName STRUCT_TREE_ROOT = name("StructTreeRoot");
  private static final COSName KIDS = name("K");
  private static final COSName SIZE = name("Size");
  private static final COSName INDEX = name("Index");
  private static final COSName OBJECT_COUNT = name("N");
  private static final Set<COSName> FLATE = Set.of(name("FlateDecode"), name("Fl"));

  private PdfActiveContent() {}

  private static COSName name(String name) {
    return COSName.getPDFName(name);
  }

  /**
   * Inspects the file now, before the caller opens a transaction or locks a row (TAR-39): {@link
   * #found} on the same array then gives this verdict without parsing it again.
   *
   * @throws FileInspectionBusyException if another file kept the inspection too long, or this one
   *     ran past its deadline: no verdict, so the caller stops before the use case, which would
   *     parse the file again under its locks
   */
  static void inspectAhead(byte[] content) {
    String digest = digest(content); // of the bytes inspected, should anyone change them after
    Boolean active = inspect(content);
    if (active == null) {
      throw new FileInspectionBusyException();
    }
    AHEAD.put(content, new Ahead(active, digest));
  }

  /**
   * Whether the file has an active entry, or cannot be read well enough to tell.
   *
   * @throws FileInspectionBusyException if another file kept the inspection too long
   */
  static boolean found(byte[] content) {
    Ahead ahead = AHEAD.get(content);
    if (ahead != null && ahead.digest().equals(digest(content))) {
      return ahead.active();
    }
    return !Boolean.FALSE.equals(inspect(content));
  }

  private static String digest(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is in every Java", e);
    }
  }

  /** The verdict, or null if the inspection ran past its deadline: no verdict to keep. */
  private static @Nullable Boolean inspect(byte[] content) {
    if (content.length > MAX_FILE) {
      return true;
    }
    try {
      if (!INSPECTION.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw new FileInspectionBusyException();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FileInspectionBusyException();
    }
    try {
      return parseAndInspect(content);
    } finally {
      INSPECTION.release();
    }
  }

  private static @Nullable Boolean parseAndInspect(byte[] content) {
    try {
      return parse(content);
    } finally {
      forgetNames();
    }
  }

  private static @Nullable Boolean parse(byte[] content) {
    BoundedParser parser = null;
    boolean active;
    try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(content)) {
      parser = new BoundedParser(source);
      try (PDDocument document = parser.parse(false)) {
        active = hasActiveObject(parser, document.getDocument()) || parser.refused;
      }
    } catch (IOException
        | UncheckedIOException
        | IllegalArgumentException
        | IllegalStateException
        | ClassCastException
        | IndexOutOfBoundsException
        | NegativeArraySizeException
        | ArrayStoreException
        | BufferUnderflowException
        | BufferOverflowException
        | EmptyStackException
        | ProviderException
        | NoSuchElementException
        | ConcurrentModificationException
        | NullPointerException
        | ArithmeticException
        | UnsupportedOperationException
        | StackOverflowError
        | OutOfMemoryError e) {
      // The inspection boundary: whatever a malformed file makes the parser throw refuses it, and
      // nothing of the file nor the message of PDFBox is logged. BE-SPR-COD-05 forbids catching
      // RuntimeException itself, so the unchecked exceptions of the JDK are listed.
      active = true;
    }
    return (parser != null && parser.isLate()) ? null : active;
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
    Set<COSDictionary> structure = structureElements(trailer);
    return structure == null
        || reachesAnActiveDictionary(parser, pending, structure)
        || parser.refused;
  }

  /**
   * The value a reference leads to, following at most {@link #MAX_HOPS} references in a row: a
   * cycle of references, or a longer chain, refuses the file.
   */
  static @Nullable COSBase dereference(COSObject reference) throws IOException {
    COSBase target = reference.getObject();
    for (int hops = 1; target instanceof COSObject next; hops++) {
      if (hops >= MAX_HOPS) {
        throw new IOException("Too many references in a row");
      }
      target = next.getObject();
    }
    return target;
  }

  private static boolean reachesAnActiveDictionary(
      BoundedParser parser, Deque<COSBase> pending, Set<COSDictionary> structure)
      throws IOException {
    // Every value once: references are resolved and arrays opened, direct or indirect alike.
    // The walk keeps its own stack, so a deep file costs memory bounded by the values, not depth.
    Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    while (!pending.isEmpty()) {
      COSBase value = pending.pop();
      // a reference is a node too: visited once and counted, so a cycle cannot spin
      if (!visited.add(value)) {
        continue;
      }
      if (visited.size() > 2 * MAX_VALUES || parser.isLate()) {
        return true;
      }
      if (value instanceof COSObject reference) {
        COSBase target = dereference(reference);
        if (target != null) {
          pending.push(target);
        }
        continue;
      }
      if (value instanceof COSDictionary dictionary) {
        if (ActiveEntries.isActive(dictionary, structure)) {
          return true;
        }
        pending.addAll(dictionary.getValues());
      } else if (value instanceof COSArray array) {
        array.forEach(pending::push);
      }
    }
    return false;
  }

  /**
   * The dictionaries reached through {@code /K} from the {@code /StructTreeRoot} of the catalog,
   * resolved and opened as any other value; null if there are more than the file may have values.
   */
  private static @Nullable Set<COSDictionary> structureElements(COSDictionary trailer)
      throws IOException {
    Set<COSDictionary> elements = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<COSBase> pending = new ArrayDeque<>();
    if (trailer.getDictionaryObject(ROOT) instanceof COSDictionary catalog
        && catalog.getDictionaryObject(STRUCT_TREE_ROOT) instanceof COSDictionary tree) {
      COSBase kids = tree.getItem(KIDS);
      if (kids != null) {
        pending.push(kids);
      }
    }
    while (!pending.isEmpty()) {
      COSBase value = pending.pop();
      if (value instanceof COSObject reference) {
        value = dereference(reference);
      }
      if (value == null || !visited.add(value)) {
        continue;
      }
      if (visited.size() > MAX_VALUES) {
        return null;
      }
      if (value instanceof COSDictionary element) {
        elements.add(element);
        COSBase kids = element.getItem(KIDS);
        if (kids != null) {
          pending.push(kids);
        }
      } else if (value instanceof COSArray array) {
        array.forEach(pending::push);
      }
    }
    return elements;
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
    private boolean readingTable;
    private int tableLines;
    private boolean refused;
    private final long deadline = System.nanoTime() + deadlineMillis * 1_000_000;
    private boolean late;

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
      if (isLate()) {
        refuse("Past the deadline");
      }
    }

    /** Whether the inspection ran past its deadline; once late, it stays late. */
    boolean isLate() {
      late = late || System.nanoTime() - deadline >= 0;
      return late;
    }

    /** Each entry of a classic table is one line, read before PDFBox keeps it. */
    @Override
    protected boolean parseXrefTable(long startByteOffset) throws IOException {
      readingTable = true;
      try {
        return super.parseXrefTable(startByteOffset);
      } finally {
        readingTable = false;
      }
    }

    @Override
    protected String readLine() throws IOException {
      if (readingTable && ++tableLines > MAX_OBJECTS + 1) {
        refuse("Too many entries in the table");
      }
      return super.readLine();
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
