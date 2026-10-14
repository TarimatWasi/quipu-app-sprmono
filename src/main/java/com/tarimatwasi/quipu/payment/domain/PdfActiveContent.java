package com.tarimatwasi.quipu.payment.domain;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Looks for the names that make a PDF run something: JavaScript, a launch action, an action on
 * open, additional actions or an embedded file. A voucher is uploaded by a guest and opened by the
 * administrator, so it must be a document and not a program.
 *
 * <p>It reads the file as the syntax of a PDF is written, only as far as needed to tell a name that
 * is part of a dictionary from one that is not: the text of a string {@code (...)} (nested
 * parentheses and backslash escapes included), a hex string, a comment and the body of a stream are
 * skipped, so a page that merely prints {@code /JS} is not refused. The {@code #hh} escapes of a
 * name are decoded, so {@code /J#61vaScript} is found.
 *
 * <p>This is defense in depth and best effort, not a PDF parser: it cannot see inside a compressed
 * object stream, and a file built to be read differently by a viewer and by this scan can get
 * through. The voucher is also served as a download and never inline.
 */
final class PdfActiveContent {

  private static final Set<String> NAMES =
      Set.of("JavaScript", "JS", "Launch", "OpenAction", "AA", "EmbeddedFile");

  private static final byte[] STREAM = "stream".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] END_STREAM = "endstream".getBytes(StandardCharsets.US_ASCII);

  /** The longest name worth comparing; a longer one cannot be any of the above. */
  private static final int MAX_NAME = 16;

  private PdfActiveContent() {}

  /** Whether the file names any of the active-content entries outside strings and streams. */
  static boolean found(byte[] content) {
    int i = 0;
    while (i < content.length) {
      byte b = content[i];
      if (b == '%') {
        i = endOfComment(content, i);
      } else if (b == '(') {
        i = endOfString(content, i);
      } else if (b == '<') {
        i = i + 1 < content.length && content[i + 1] == '<' ? i + 2 : endOfHexString(content, i);
      } else if (b == '/') {
        int end = endOfName(content, i + 1);
        if (NAMES.contains(decodedName(content, i + 1, end))) {
          return true;
        }
        i = end;
      } else if (delimiter(b)) {
        i++;
      } else {
        int end = endOfToken(content, i);
        i = isKeyword(content, i, end, STREAM) ? endOfStream(content, end) : end;
      }
    }
    return false;
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

  private static int endOfName(byte[] content, int from) {
    int i = from;
    while (i < content.length && !delimiter(content[i])) {
      i++;
    }
    return i;
  }

  private static int endOfToken(byte[] content, int from) {
    return endOfName(content, from);
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
