package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** RF-15: the type of a voucher comes from its bytes, never from the client. */
class VoucherFileTest {

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  private static byte[] padded(byte[] header, int size) {
    byte[] out = new byte[size];
    System.arraycopy(header, 0, out, 0, header.length);
    return out;
  }

  @Test
  void aJpegIsKnownByItsFirstThreeBytes() {
    assertThat(VoucherFile.typeOf(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        .contains(VoucherType.JPEG);
    assertThat(VoucherType.JPEG.contentType()).isEqualTo("image/jpeg");
    assertThat(VoucherType.JPEG.extension()).isEqualTo("jpg");
  }

  @Test
  void aPngIsKnownByItsEightByteSignature() {
    assertThat(VoucherFile.typeOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))
        .contains(VoucherType.PNG);
    assertThat(VoucherType.PNG.contentType()).isEqualTo("image/png");
  }

  @Test
  void aPdfIsKnownByItsHeader() {
    assertThat(VoucherFile.typeOf("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)))
        .contains(VoucherType.PDF);
    assertThat(VoucherType.PDF.contentType()).isEqualTo("application/pdf");
  }

  @Test
  void aProgramRenamedToAnImageOrAPdfIsNotAVoucher() {
    assertThat(VoucherFile.typeOf(bytes('M', 'Z', 0x90, 0, 3, 0, 0, 0))).isEmpty();
    assertThat(
            VoucherFile.typeOf("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
    assertThat(VoucherFile.typeOf("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
  }

  @Test
  void aSignatureThatIsCutShortOrAlmostRightIsNotAccepted() {
    assertThat(VoucherFile.typeOf(bytes(0xFF, 0xD8))).isEmpty();
    assertThat(VoucherFile.typeOf(bytes(0x89, 0x50, 0x4E, 0x47))).isEmpty();
    assertThat(VoucherFile.typeOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0B))).isEmpty();
    assertThat(VoucherFile.typeOf("%PDF".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    assertThat(VoucherFile.typeOf(" %PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEmpty();
  }

  @Test
  void anEmptyFileIsNotAVoucher() {
    assertThat(VoucherFile.typeOf(new byte[0])).isEmpty();
  }

  @Test
  void theFileMayHaveFiveMegabytesButNotOneByteMore() {
    byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

    assertThat(VoucherFile.typeOf(padded(pdf, VoucherFile.MAX_BYTES))).contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(padded(pdf, VoucherFile.MAX_BYTES + 1))).isEmpty();
    assertThat(VoucherFile.MAX_BYTES).isEqualTo(5 * 1024 * 1024);
  }

  @Test
  void everyTypeHasADistinctContentTypeAndExtension() {
    assertThat(Arrays.stream(VoucherType.values()).map(VoucherType::contentType).distinct())
        .hasSize(3);
    assertThat(Arrays.stream(VoucherType.values()).map(VoucherType::extension).distinct())
        .hasSize(3);
  }

  private static byte[] pdf(String body) {
    return ("%PDF-1.7\n" + body + "\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
  }

  private static boolean accepted(String body) {
    return VoucherFile.typeOf(pdf(body)).isPresent();
  }

  @Test
  void aPlainPdfIsAccepted() {
    assertThat(
            accepted(
                "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj 3 0 obj << /Length 4 /Filter /FlateDecode >>"))
        .isTrue();
    assertThat(VoucherFile.typeOf(pdf("<< /Type /Catalog >>"))).contains(VoucherType.PDF);
  }

  @Test
  void aPdfWithAnActiveEntryAsAKeyIsNotAVoucher() {
    for (String key :
        new String[] {"/JavaScript", "/JS", "/Launch", "/OpenAction", "/AA", "/EmbeddedFile"}) {
      assertThat(accepted("1 0 obj << /Type /Catalog " + key + " 5 0 R >> endobj"))
          .as(key)
          .isFalse();
      assertThat(accepted("<< /Pages 1 0 R " + key + " (x) >>")).as(key).isFalse();
    }
  }

  @Test
  void theTypeOfAnActionAndTheKindOfAFileAreAsActiveAsTheirKeys() {
    assertThat(accepted("<< /Type /Action /S /JavaScript /JS (app.alert(1)) >>")).isFalse();
    assertThat(accepted("<< /S /Launch /F (cmd.exe) >>")).isFalse();
    assertThat(accepted("<< /Type /EmbeddedFile /Length 3 >>")).isFalse();
    assertThat(accepted("<< /Type /Action /S /URI /URI (https://example.com) >>")).isTrue();
  }

  @Test
  void theNamesAreFoundWhateverFollowsThem() {
    assertThat(accepted("<</S/JavaScript/JS(app.alert(1))>>")).isFalse();
    assertThat(accepted("<< /Names << /JavaScript 5 0 R >> >>")).isFalse();
    assertThat(accepted("<</OpenAction<</S/URI>>>>")).isFalse();
    assertThat(accepted("<< /Pages 1 0 R /AA<</O 5 0 R>> >>")).isFalse();
  }

  @Test
  void anEscapedNameIsDecodedBeforeItIsComparedSoItCannotHideTheToken() {
    assertThat(accepted("<< /J#61vaScript 5 0 R >>")).isFalse();
    assertThat(accepted("<< /#4A#53 5 0 R >>")).isFalse();
    assertThat(accepted("<< /Open#41ction 5 0 R >>")).isFalse();
    assertThat(accepted("<< /S /L#61unch >>")).isFalse();
  }

  @Test
  void aNameThatMerelyStartsLikeOneOfThemIsNotActiveContent() {
    assertThat(accepted("<< /JSON 1 /AAPL 2 /Launcher 3 /JavaScripts 4 /Actions 5 >>")).isTrue();
  }

  @Test
  void theResourcesOfAPageMayUseTheseWordsAsIdentifiers() {
    assertThat(accepted("<< /Resources << /Font << /JS 2 0 R /AA 3 0 R >> >> >>")).isTrue();
    assertThat(
            accepted(
                "<< /Resources << /XObject << /Launch 4 0 R >> /ExtGState << /JS << /CA 1 >> >> >> >>"))
        .isTrue();
    assertThat(
            accepted(
                "<< /ColorSpace << /OpenAction [/ICCBased 5 0 R] >> /Pattern << /AA 6 0 R >> >>"))
        .isTrue();
    assertThat(accepted("<< /Properties << /EmbeddedFile 7 0 R /JavaScript 8 0 R >> >>")).isTrue();
  }

  @Test
  void anActiveKeyNextToAResourceDictionaryIsStillFound() {
    assertThat(accepted("<< /Resources << /Font << /F1 2 0 R >> >> /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Font << /F1 << /Type /Font /JS (x) >> >> >>")).isFalse();
    assertThat(accepted("<< /Font << /F1 2 0 R >> /OpenAction 5 0 R >>")).isFalse();
  }

  @Test
  void aNameAsTheValueOfAnotherKeyOrInAnArrayIsNotActiveContent() {
    assertThat(accepted("<< /BaseFont /JS /Subtype /Type1 /Name /AA >>")).isTrue();
    assertThat(accepted("<< /Filter [/JS /Launch] /Kids [ 1 0 R 2 0 R ] >>")).isTrue();
    assertThat(accepted("<< /Type /Font /Subtype /JavaScript >>")).isTrue();
  }

  @Test
  void anActiveKeyAfterAnyKindOfValueIsFound() {
    assertThat(accepted("<< /Subtype /Foo /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Count 5 /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Parent 5 0 R /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Kids [ 1 0 R 2 0 R ] /JS (x) >>")).isFalse();
    assertThat(accepted("<< /A << /B 1 >> /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Flag true /Next null /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Foo (text) /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Foo <2f4a53> /JS (x) >>")).isFalse();
    assertThat(accepted("<< /Matrix [1 0 0 1 0 0] /Rect [0 0 1 1] /AA << >> >>")).isFalse();
  }

  @Test
  void theWordsInsideTheTextOfThePageAreNotNames() {
    assertThat(accepted("BT (Pago por JavaScript, Launch y OpenAction) Tj ET")).isTrue();
  }

  @Test
  void aNameInsideAStringACommentOrAStreamIsNotActiveContent() {
    assertThat(accepted("<< /T (/JS) >>")).isTrue();
    assertThat(accepted("<< /T (text (nested /JavaScript) more) >>")).isTrue();
    assertThat(accepted("<< /T (an escaped paren \\) then /Launch) >>")).isTrue();
    assertThat(accepted("% a comment about /OpenAction\n1 0 obj << /Type /Page >>")).isTrue();
    assertThat(accepted("<< /Contents <2f4a53> >>")).isTrue();
    assertThat(accepted("<< /Length 12 >>\nstream\nbinary /JS /AA data\nendstream\nendobj"))
        .isTrue();
  }

  @Test
  void aNameThatFollowsAStringAStreamOrACommentIsStillFound() {
    assertThat(accepted("<< /T (text) /JS 5 0 R >>")).isFalse();
    assertThat(accepted("<< /T (text (nested)) /Launch 5 0 R >>")).isFalse();
    assertThat(accepted("<< /Length 4 >>\nstream\nabcd\nendstream\n<< /OpenAction 1 0 R >>"))
        .isFalse();
    assertThat(accepted("% a comment\n<< /AA << >> >>")).isFalse();
    assertThat(accepted("<< /X <2f4a53> /EmbeddedFile 1 0 R >>")).isFalse();
  }

  @Test
  void anUnclosedStringStreamOrDictionaryDoesNotMakeTheScanFail() {
    assertThat(accepted("(never closed /JS")).isTrue();
    assertThat(accepted("<< >>\nstream\nnever ends /JS")).isTrue();
    assertThat(accepted("<2f4a53 never closed")).isTrue();
    assertThat(accepted("<< /Type /Catalog ")).isTrue();
    assertThat(accepted(">> ] ] >> /JS")).isTrue();
  }

  @Test
  void aNestingNoRealVoucherHasIsRefusedBecauseItCouldHideAnEntry() {
    assertThat(accepted("<< /A ".repeat(5000) + "<< /JS (x) >>")).isFalse();
    assertThat(accepted("[ ".repeat(5000))).isFalse();
    assertThat(accepted("<< /A ".repeat(150) + ">> ".repeat(150))).isTrue();
  }

  @Test
  void aVeryLongNameIsNotMistakenForAShortOne() {
    assertThat(accepted("<< /" + "A".repeat(5000) + " 1 >>")).isTrue();
  }

  @Test
  void anImageIsNotScannedForPdfNames() {
    byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, '/', 'J', 'S', ' ');

    assertThat(VoucherFile.typeOf(png)).contains(VoucherType.PNG);
  }
}
