package com.tarimatwasi.quipu.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentTextTest {

  private static String chars(int codePoint) {
    return new String(Character.toChars(codePoint));
  }

  @Test
  void hasUnsafeCharacter_findsControlFormatAndSeparatorCharacters() {
    for (String text :
        new String[] {
          "line\nbreak",
          "tab\there",
          "cr\rhere",
          "nul\u0000x",
          "esc\u001Bx",
          "del\u007Fx",
          "bidi\u202Eevil",
          "zero\u200Bwidth",
          "bom\uFEFFx",
          "ls\u2028x",
          "ps\u2029x"
        }) {
      assertThat(PaymentText.hasUnsafeCharacter(text)).as(text).isTrue();
    }
  }

  @Test
  void hasUnsafeCharacter_findsTheOnesOutsideTheBasicPlaneToo() {
    // two UTF-16 units each: U+E0001 is a language tag, U+1D173 a musical format character
    assertThat(PaymentText.hasUnsafeCharacter("mal" + chars(0xE0001))).isTrue();
    assertThat(PaymentText.hasUnsafeCharacter("mal" + chars(0x1D173))).isTrue();
  }

  @Test
  void hasUnsafeCharacter_letsOrdinaryTextThrough() {
    for (String text :
        new String[] {
          "Yape", "Transferencia BCP", "Ñandú 2026", "pago \u00A0 ok", chars(0x1F600)
        }) {
      assertThat(PaymentText.hasUnsafeCharacter(text)).as(text).isFalse();
    }
  }

  @Test
  void characters_countsCodePointsAndNotUtf16Units() {
    assertThat(PaymentText.characters("abc")).isEqualTo(3);
    assertThat(PaymentText.characters(chars(0x1F600).repeat(50))).isEqualTo(50);
    assertThat(chars(0x1F600).repeat(50).length()).isEqualTo(100);
  }
}
