package com.tarimatwasi.quipu.payment.application;

/** What the free text of a payment (the reason of a rejection, the payment method) may contain. */
final class PaymentText {

  private PaymentText() {}

  /**
   * Control characters (line breaks and tabs included), and the format and line-separator ones that
   * disguise text: the bidi override U+202E, the zero-width space U+200B, the language tags of
   * U+E0000, U+2028 and U+2029. The text is read by code points, so the characters outside the
   * basic plane are not missed.
   */
  static boolean hasUnsafeCharacter(String text) {
    return text.codePoints()
        .anyMatch(
            c -> {
              int type = Character.getType(c);
              return Character.isISOControl(c)
                  || type == Character.FORMAT
                  || type == Character.LINE_SEPARATOR
                  || type == Character.PARAGRAPH_SEPARATOR;
            });
  }

  /** The characters of a text as the column counts them: code points, not UTF-16 units. */
  static int characters(String text) {
    return text.codePointCount(0, text.length());
  }
}
