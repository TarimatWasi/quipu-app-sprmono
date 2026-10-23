package com.tarimatwasi.quipu.shared.file;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Looks for the entries that make a PDF run something: JavaScript, a launch action, an action on
 * open, additional actions or an embedded file. A voucher or a document of the onboarding is
 * uploaded by a guest and opened by the administrator, so it must be a document and not a program.
 *
 * <p>It follows the structure of the file just far enough to tell an entry from a name that only
 * looks like one. A name counts when it is a <em>key</em> of a dictionary ({@code /OpenAction},
 * {@code /AA}, {@code /JS}, {@code /JavaScript} in the names tree...), or the value of {@code /S}
 * (the type of an action, as in {@code /S /Launch}) or of {@code /Type} (as in {@code /Type
 * /EmbeddedFile}). It does not count in the text of a string {@code (...)}, a hex string, a comment
 * or the body of a stream; in the values of other keys ({@code /BaseFont /JS}); in an array; or as
 * a key of a resource dictionary ({@code /Font << /JS 2 0 R >>}, where the keys are only the
 * identifiers a page gives its fonts, images and graphics states). The {@code #hh} escapes of a
 * name are decoded, so {@code /J#61vaScript} is found. A file that nests more than 200 levels is
 * refused.
 *
 * <p>This is defense in depth and best effort, not a PDF parser: it cannot see inside a compressed
 * object stream, and a file built to be read differently by a viewer and by this scan can get
 * through. The voucher is also served as a download and never inline.
 */
final class PdfActiveContent {

  private static final Set<String> NAMES =
      Set.of("JavaScript", "JS", "Launch", "OpenAction", "AA", "EmbeddedFile");

  /** The keys whose value names the kind of the object, so a name there says what it is. */
  private static final Set<String> KIND_KEYS = Set.of("S", "Type");

  /** The dictionaries whose keys are identifiers chosen by the page, not entries of the format. */
  private static final Set<String> RESOURCE_KEYS =
      Set.of("Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading", "Properties");

  private static final byte[] STREAM = "stream".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] END_STREAM = "endstream".getBytes(StandardCharsets.US_ASCII);

  /** The longest name worth comparing; a longer one cannot be any of the above. */
  private static final int MAX_NAME = 16;

  /**
   * A voucher never nests dictionaries and arrays this deep. A file that does is refused: the depth
   * could hide an entry from the scan, and the scan keeps a frame for each level.
   */
  private static final int MAX_DEPTH = 200;

  private PdfActiveContent() {}

  /** A dictionary or an array that is open, with what the next name in it is. */
  private static final class Frame {
    private final boolean array;
    private final boolean resource;
    private boolean expectKey = true;
    private String key = "";

    Frame(boolean array, boolean resource) {
      this.array = array;
      this.resource = resource;
    }
  }

  /** The open dictionaries and arrays. */
  private static final class Nesting {
    private final Deque<Frame> open = new ArrayDeque<>();
    private boolean tooDeep;

    Frame top() {
      return open.peek();
    }

    void openArray() {
      push(new Frame(true, false));
    }

    void openDictionary() {
      Frame parent = open.peek();
      boolean resource =
          parent != null
              && !parent.array
              && !parent.expectKey
              && RESOURCE_KEYS.contains(parent.key);
      push(new Frame(false, resource));
    }

    private void push(Frame frame) {
      if (open.size() >= MAX_DEPTH) {
        tooDeep = true;
      } else {
        open.push(frame);
      }
    }

    /** Closes the innermost level; what was closed was the value of the key before it. */
    void close() {
      open.poll();
      valueDone();
    }

    /** The value of the current key is complete, so the next name in the dictionary is a key. */
    void valueDone() {
      Frame top = open.peek();
      if (top != null && !top.array && !top.expectKey) {
        top.expectKey = true;
        top.key = "";
      }
    }
  }

  /** Whether the file has an active entry outside strings, comments and streams. */
  static boolean found(byte[] content) {
    Nesting nesting = new Nesting();
    int i = 0;
    while (i < content.length) {
      byte b = content[i];
      if (b == '%') {
        i = endOfComment(content, i);
      } else if (b == '(') {
        i = endOfString(content, i);
        nesting.valueDone();
      } else if (b == '<') {
        i = afterOpenAngle(content, i, nesting);
        if (nesting.tooDeep) {
          return true;
        }
      } else if (b == '>') {
        if (i + 1 < content.length && content[i + 1] == '>') {
          nesting.close();
          i += 2;
        } else {
          i++;
        }
      } else if (b == '[') {
        nesting.openArray();
        if (nesting.tooDeep) {
          return true;
        }
        i++;
      } else if (b == ']') {
        nesting.close();
        i++;
      } else if (b == '/') {
        int end = endOfToken(content, i + 1);
        if (isActive(nesting, decodedName(content, i + 1, end))) {
          return true;
        }
        i = end;
      } else if (delimiter(b)) {
        i++;
      } else {
        i = afterRegularToken(content, i, nesting);
      }
    }
    return false;
  }

  /** A {@code <<} opens a dictionary; a single {@code <} starts a hex string, which is skipped. */
  private static int afterOpenAngle(byte[] content, int from, Nesting nesting) {
    if (from + 1 < content.length && content[from + 1] == '<') {
      nesting.openDictionary();
      return from + 2;
    }
    nesting.valueDone();
    return endOfHexString(content, from);
  }

  /** Reads a name; true if it is an entry of active content where it stands. */
  private static boolean isActive(Nesting nesting, String name) {
    Frame frame = nesting.top();
    if (frame == null || frame.array) {
      return false;
    }
    boolean named = NAMES.contains(name);
    if (frame.expectKey) {
      frame.key = name;
      frame.expectKey = false;
      return named && !frame.resource;
    }
    boolean active = named && KIND_KEYS.contains(frame.key);
    nesting.valueDone();
    return active;
  }

  /** A number, a keyword or a reference; a stream keyword skips the body of the stream. */
  private static int afterRegularToken(byte[] content, int from, Nesting nesting) {
    int end = endOfToken(content, from);
    if (isKeyword(content, from, end, STREAM)) {
      return endOfStream(content, end);
    }
    Frame top = nesting.top();
    if (top == null || top.array || top.expectKey) {
      return end;
    }
    // A value that is a number, true, false or null; "5 0 R" is a reference of three tokens.
    int next = isNumber(content, from, end) ? endOfReference(content, end) : end;
    nesting.valueDone();
    return next;
  }

  /**
   * The end of {@code 0 R} after a first number, or the end of that number if it is not a
   * reference.
   */
  private static int endOfReference(byte[] content, int afterNumber) {
    int generationStart = skipSpace(content, afterNumber);
    int generationEnd = endOfToken(content, generationStart);
    if (generationEnd == generationStart || !isNumber(content, generationStart, generationEnd)) {
      return afterNumber;
    }
    int markerStart = skipSpace(content, generationEnd);
    int markerEnd = endOfToken(content, markerStart);
    return markerEnd - markerStart == 1 && content[markerStart] == 'R' ? markerEnd : afterNumber;
  }

  private static int skipSpace(byte[] content, int from) {
    int i = from;
    while (i < content.length
        && (content[i] == 0
            || content[i] == 9
            || content[i] == 10
            || content[i] == 12
            || content[i] == 13
            || content[i] == 32)) {
      i++;
    }
    return i;
  }

  private static boolean isNumber(byte[] content, int from, int end) {
    if (end == from) {
      return false;
    }
    for (int i = from; i < end; i++) {
      byte b = content[i];
      if (!(b >= '0' && b <= '9') && b != '.' && b != '-' && b != '+') {
        return false;
      }
    }
    return true;
  }

  private static int endOfComment(byte[] content, int from) {
    int i = from;
    while (i < content.length && content[i] != '\n' && content[i] != '\r') {
      i++;
    }
    return i;
  }

  /** The text of a string runs to the parenthesis that closes it; inner pairs nest. */
  private static int endOfString(byte[] content, int from) {
    int depth = 1;
    int i = from + 1;
    while (i < content.length && depth > 0) {
      if (content[i] == '\\') {
        i++;
      } else if (content[i] == '(') {
        depth++;
      } else if (content[i] == ')') {
        depth--;
      }
      i++;
    }
    return Math.min(i, content.length);
  }

  private static int endOfHexString(byte[] content, int from) {
    int i = from + 1;
    while (i < content.length && content[i] != '>') {
      i++;
    }
    return Math.min(i + 1, content.length);
  }

  private static int endOfToken(byte[] content, int from) {
    int i = from;
    while (i < content.length && !delimiter(content[i])) {
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

  private static boolean isKeyword(byte[] content, int from, int end, byte[] keyword) {
    if (end - from != keyword.length) {
      return false;
    }
    for (int i = 0; i < keyword.length; i++) {
      if (content[from + i] != keyword[i]) {
        return false;
      }
    }
    return true;
  }

  /** The body of a stream runs to {@code endstream}, or to the end of the file. */
  private static int endOfStream(byte[] content, int from) {
    for (int i = from; i + END_STREAM.length <= content.length; i++) {
      if (isKeyword(content, i, i + END_STREAM.length, END_STREAM)) {
        return i + END_STREAM.length;
      }
    }
    return content.length;
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
