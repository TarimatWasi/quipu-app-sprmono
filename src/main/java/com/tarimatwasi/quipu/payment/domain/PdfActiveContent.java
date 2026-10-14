package com.tarimatwasi.quipu.payment.domain;

import java.util.Set;

/**
 * Looks for the names that make a PDF run something: JavaScript, a launch action, an action on
 * open, additional actions or an embedded file. A voucher is uploaded by a guest and opened by the
 * administrator, so it must be a document and not a program.
 *
 * <p>This is defense in depth and best effort: it reads the names of the file as they are written
 * (it decodes the {@code #hh} escapes, so {@code /J#61vaScript} is found), but it cannot see inside
 * a compressed object stream. The voucher is also served as a download and never inline.
 */
final class PdfActiveContent {

  private static final Set<String> NAMES =
      Set.of("JavaScript", "JS", "Launch", "OpenAction", "AA", "EmbeddedFile");

  /** The longest name worth comparing; a longer one cannot be any of the above. */
  private static final int MAX_NAME = 16;

  private PdfActiveContent() {}

  /** Whether the file names any of the active-content entries. */
  static boolean found(byte[] content) {
    int i = 0;
    while (i < content.length) {
      if (content[i] != '/') {
        i++;
        continue;
      }
      i++;
      StringBuilder name = new StringBuilder();
      while (i < content.length && !delimiter(content[i])) {
        int high = i + 2 < content.length && content[i] == '#' ? hex(content[i + 1]) : -1;
        int low = high >= 0 ? hex(content[i + 2]) : -1;
        int value = low >= 0 ? high * 16 + low : content[i] & 0xFF;
        i += low >= 0 ? 3 : 1;
        if (name.length() <= MAX_NAME) {
          name.append((char) value);
        }
      }
      if (NAMES.contains(name.toString())) {
        return true;
      }
    }
    return false;
  }

  /** White space and the delimiters of PDF syntax end a name. */
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
