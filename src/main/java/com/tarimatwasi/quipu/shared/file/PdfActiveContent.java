package com.tarimatwasi.quipu.shared.file;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Looks for the entries that make a PDF run something or carry something: JavaScript, a launch
 * action, an action on open, additional actions, a form that submits or imports data, an XFA form,
 * rich media, an embedded file or a file attachment. A voucher, a document of the onboarding or a
 * contract is uploaded by one person and opened by another, so it must be a document and not a
 * program.
 *
 * <p>The scan <strong>fails closed</strong>. It does not try to follow the structure of the file,
 * because a file can be built so that the scan and a viewer read it differently (a {@code stream}
 * keyword that is never ended, a {@code (} that is never closed, a viewer that jumps by the xref
 * into what looked like the body of a stream). Instead:
 *
 * <ul>
 *   <li>Every name in the bytes of the file counts, wherever it stands: in a dictionary, in a
 *       string, in a comment or in the body of a stream. The {@code #hh} escapes are decoded, so
 *       {@code /J#61vaScript} is found; only a whole name counts, so {@code /JSON} is not {@code
 *       /JS}. A legitimate PDF that writes {@code /JavaScript} in a text, or names a font {@code
 *       /JS}, is refused: that is the price of a stricter check. High-entropy binary data (a JPEG
 *       image, compressed data) can by chance hold a short name such as {@code /JS} followed by a
 *       delimiter; that is rare (about one in 10<sup>8</sup> bytes) and also refuses the file.
 *   <li>Every stream whose body starts with a zlib header is inflated with {@link Inflater} and the
 *       inflated bytes are scanned for the same names. That reaches the object streams ({@code
 *       /ObjStm}), where a modern PDF keeps its dictionaries compressed. A stream that the
 *       dictionary before it marks {@code /FlateDecode} must inflate cleanly to its end; if it does
 *       not (corrupt, cut, or encrypted with {@code /Encrypt}) the file is refused, because what a
 *       viewer would read there cannot be checked. An encrypted PDF with compressed streams is
 *       therefore refused.
 *   <li>At most {@link #MAX_INFLATED} bytes are inflated over the whole file (more than six times
 *       the 5 MB upload limit); a file that needs more is refused, so a decompression bomb costs a
 *       bounded time. There is no separate ratio limit: the total bounds the work, and a blank page
 *       compressed with Flate legitimately reaches a ratio near 1000:1.
 *   <li>The filters that could hide names from both passes ({@code /ASCIIHexDecode}, {@code
 *       /ASCII85Decode}, {@code /LZWDecode}, {@code /RunLengthDecode} and their short forms) refuse
 *       the file, as does an object stream with {@code /DecodeParms}, whose predictor would change
 *       the bytes after inflating.
 * </ul>
 *
 * <p>The cost is linear in the size of the file plus the inflated bytes, and nothing recurses. This
 * is defense in depth, not a PDF parser; the file is also served as a download and never inline.
 * Known limits: a stream whose dictionary is separated from its body by a decoy {@code stream}
 * keyword hidden in a string is judged by the names after the decoy only (its body is still
 * inflated if it starts with a zlib header); a filter for images ({@code /DCTDecode}...) is not
 * decoded.
 */
final class PdfActiveContent {

  private static final Set<String> NAMES =
      Set.of(
          "JavaScript",
          "JS",
          "Launch",
          "OpenAction",
          "AA",
          "EmbeddedFile",
          "EmbeddedFiles",
          "EF",
          "FileAttachment",
          "XFA",
          "RichMedia",
          "SubmitForm",
          "ImportData",
          "GoToE");

  /** Filters whose output this scan does not decode and that could hold names a viewer reads. */
  private static final Set<String> HIDING_FILTERS =
      Set.of(
          "ASCIIHexDecode",
          "AHx",
          "ASCII85Decode",
          "A85",
          "LZWDecode",
          "LZW",
          "RunLengthDecode",
          "RL");

  private static final byte[] STREAM = "stream".getBytes(StandardCharsets.US_ASCII);

  /** The longest name worth comparing; a longer one cannot be any of the above. */
  private static final int MAX_NAME = 16;

  /** The bytes kept from one inflated chunk to the next, so a name cut between them is whole. */
  private static final int OVERLAP = 3 * MAX_NAME + 2;

  /** The most bytes inflated over the whole file. */
  static final int MAX_INFLATED = 32 * 1024 * 1024;

  /** The bytes inflated and scanned at a time. */
  static final int CHUNK = 64 * 1024;

  private PdfActiveContent() {}

  /** What the dictionary before a stream says about it, as far as its names show. */
  private static final class Window {
    private boolean flate;
    private boolean objectStream;
    private boolean decodeParms;

    void see(String name) {
      switch (name) {
        case "FlateDecode", "Fl" -> flate = true;
        case "ObjStm" -> objectStream = true;
        case "DecodeParms", "DP" -> decodeParms = true;
        default -> {
          // other names say nothing about how the stream is encoded
        }
      }
    }
  }

  /** Whether the file has, or could hide, an active entry. */
  static boolean found(byte[] content) {
    Window window = new Window();
    long[] budget = {MAX_INFLATED};
    int i = 0;
    while (i < content.length) {
      byte b = content[i];
      if (b == '/') {
        int end = endOfToken(content, i + 1, content.length);
        String name = decodedName(content, i + 1, end);
        if (NAMES.contains(name) || HIDING_FILTERS.contains(name)) {
          return true;
        }
        window.see(name);
        i = end;
      } else if (delimiter(b)) {
        i++;
      } else {
        int end = endOfToken(content, i, content.length);
        if (isStreamKeyword(content, i, end)) {
          if (streamIsActive(content, end, window, budget)) {
            return true;
          }
          window = new Window();
        }
        i = end;
      }
    }
    return false;
  }

  /** Inflates the body after a {@code stream} keyword when it is (or claims to be) Flate. */
  private static boolean streamIsActive(
      byte[] content, int afterKeyword, Window window, long[] budget) {
    if (window.objectStream && window.decodeParms) {
      return true;
    }
    int body = skipSpace(content, afterKeyword);
    if (!zlibHeader(content, body)) {
      return window.flate;
    }
    return switch (inflate(content, body, budget)) {
      case CLEAN -> false;
      case ACTIVE, TOO_BIG -> true;
      // Not Flate after all, unless its dictionary said it is.
      case BROKEN -> window.flate;
    };
  }

  private enum Inflated {
    CLEAN,
    ACTIVE,
    BROKEN,
    TOO_BIG
  }

  private static Inflated inflate(byte[] content, int from, long[] budget) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(content, from, content.length - from);
      byte[] buffer = new byte[OVERLAP + CHUNK];
      int kept = 0;
      while (!inflater.finished()) {
        int read = inflater.inflate(buffer, kept, CHUNK);
        if (read == 0 && !inflater.finished()) {
          return Inflated.BROKEN;
        }
        budget[0] -= read;
        if (budget[0] < 0) {
          return Inflated.TOO_BIG;
        }
        int length = kept + read;
        if (hasActiveName(buffer, length, inflater.finished())) {
          return Inflated.ACTIVE;
        }
        kept = Math.min(OVERLAP, length);
        System.arraycopy(buffer, length - kept, buffer, 0, kept);
      }
      return Inflated.CLEAN;
    } catch (DataFormatException e) {
      return Inflated.BROKEN;
    } finally {
      inflater.end();
    }
  }

  /**
   * Whether the inflated bytes name an active entry. A name that runs to the end of a chunk that is
   * not the last is left for the next chunk, which starts with the end of this one.
   */
  private static boolean hasActiveName(byte[] buffer, int length, boolean last) {
    for (int i = 0; i < length; i++) {
      if (buffer[i] == '/') {
        int end = endOfToken(buffer, i + 1, length);
        if ((last || end < length) && NAMES.contains(decodedName(buffer, i + 1, end))) {
          return true;
        }
      }
    }
    return false;
  }

  /** RFC 1950: method 8 (deflate), a window of at most 32 KB, and a header check that holds. */
  private static boolean zlibHeader(byte[] content, int at) {
    if (at + 1 >= content.length) {
      return false;
    }
    int cmf = content[at] & 0xFF;
    int flg = content[at + 1] & 0xFF;
    return (cmf & 0x0F) == 8 && (cmf >> 4) <= 7 && ((cmf << 8) | flg) % 31 == 0;
  }

  private static boolean isStreamKeyword(byte[] content, int from, int end) {
    return end - from == STREAM.length
        && Arrays.equals(content, from, end, STREAM, 0, STREAM.length);
  }

  private static int skipSpace(byte[] content, int from) {
    int i = from;
    while (i < content.length && whiteSpace(content[i])) {
      i++;
    }
    return i;
  }

  private static int endOfToken(byte[] content, int from, int limit) {
    int i = from;
    while (i < limit && !delimiter(content[i])) {
      i++;
    }
    return i;
  }

  /** The name between the slash and its end, with the {@code #hh} escapes decoded. */
  private static String decodedName(byte[] content, int from, int end) {
    StringBuilder name = new StringBuilder();
    int i = from;
    while (i < end && name.length() <= MAX_NAME) {
      int high = i + 2 < end && content[i] == '#' ? hex(content[i + 1]) : -1;
      int low = high >= 0 ? hex(content[i + 2]) : -1;
      name.append((char) (low >= 0 ? high * 16 + low : content[i] & 0xFF));
      i += low >= 0 ? 3 : 1;
    }
    return name.toString();
  }

  private static boolean whiteSpace(byte b) {
    return b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32;
  }

  /** White space and the delimiters of PDF syntax end a name or a token. */
  private static boolean delimiter(byte b) {
    return switch (b) {
      case 0, 9, 10, 12, 13, 32, '(', ')', '<', '>', '[', ']', '{', '}', '/', '%' -> true;
      default -> false;
    };
  }

  private static int hex(byte b) {
    return Character.digit((char) (b & 0xFF), 16);
  }
}
