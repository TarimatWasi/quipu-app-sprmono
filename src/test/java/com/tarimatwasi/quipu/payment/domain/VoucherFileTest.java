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

  @Test
  void aPlainPdfIsAccepted() {
    assertThat(
            VoucherFile.typeOf(
                pdf(
                    "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj 3 0 obj << /Length 4 /Filter /FlateDecode >>")))
        .contains(VoucherType.PDF);
  }

  @Test
  void aPdfThatRunsSomethingIsNotAVoucher() {
    for (String token :
        new String[] {"/JavaScript", "/JS", "/Launch", "/OpenAction", "/AA", "/EmbeddedFile"}) {
      assertThat(VoucherFile.typeOf(pdf("1 0 obj << /Type /Catalog " + token + " 5 0 R >> endobj")))
          .as(token)
          .isEmpty();
    }
  }

  @Test
  void theNamesAreFoundWhateverFollowsThem() {
    assertThat(VoucherFile.typeOf(pdf("<</S/JavaScript/JS(app.alert(1))>>"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("[/JS]"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("<</OpenAction<</S/URI>>>>"))).isEmpty();
    assertThat(VoucherFile.typeOf("%PDF-1.7\n/Launch".getBytes(StandardCharsets.ISO_8859_1)))
        .isEmpty();
  }

  @Test
  void anEscapedNameIsDecodedBeforeItIsComparedSoItCannotHideTheToken() {
    assertThat(VoucherFile.typeOf(pdf("<< /J#61vaScript 5 0 R >>"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("<< /#4A#53 5 0 R >>"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("<< /Open#41ction 5 0 R >>"))).isEmpty();
  }

  @Test
  void aNameThatMerelyStartsLikeOneOfThemIsNotActiveContent() {
    assertThat(
            VoucherFile.typeOf(pdf("<< /JSON 1 /AAPL 2 /Launcher 3 /JavaScripts 4 /Actions 5 >>")))
        .contains(VoucherType.PDF);
  }

  @Test
  void theWordsInsideTheTextOfThePageAreNotNames() {
    assertThat(VoucherFile.typeOf(pdf("BT (Pago por JavaScript, Launch y OpenAction) Tj ET")))
        .contains(VoucherType.PDF);
  }

  @Test
  void aNameInsideAStringACommentOrAStreamIsNotActiveContent() {
    assertThat(VoucherFile.typeOf(pdf("BT (/JS) Tj ET"))).contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("BT (text (nested /JavaScript) more) Tj ET")))
        .contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("BT (an escaped paren \\) then /Launch) Tj ET")))
        .contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("% a comment about /OpenAction\n1 0 obj << /Type /Page >>")))
        .contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("<< /Contents <2f4a53> >>"))).contains(VoucherType.PDF);
    assertThat(
            VoucherFile.typeOf(
                pdf("<< /Length 12 >>\nstream\nbinary /JS /AA data\nendstream\nendobj")))
        .contains(VoucherType.PDF);
  }

  @Test
  void aNameThatFollowsAStringAStreamOrACommentIsStillFound() {
    assertThat(VoucherFile.typeOf(pdf("(text) /JS 5 0 R"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("(text (nested)) /Launch 5 0 R"))).isEmpty();
    assertThat(
            VoucherFile.typeOf(
                pdf("<< /Length 4 >>\nstream\nabcd\nendstream\n<< /OpenAction 1 0 R >>")))
        .isEmpty();
    assertThat(VoucherFile.typeOf(pdf("% a comment\n<< /AA << >> >>"))).isEmpty();
    assertThat(VoucherFile.typeOf(pdf("<2f4a53> /EmbeddedFile 1 0 R"))).isEmpty();
  }

  @Test
  void anUnclosedStringOrStreamDoesNotMakeTheScanFail() {
    assertThat(VoucherFile.typeOf(pdf("(never closed /JS"))).contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("<< >>\nstream\nnever ends /JS"))).contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(pdf("<2f4a53 never closed"))).contains(VoucherType.PDF);
  }

  @Test
  void aVeryLongNameIsNotMistakenForAShortOne() {
    assertThat(VoucherFile.typeOf(pdf("<< /" + "A".repeat(5000) + " 1 >>")))
        .contains(VoucherType.PDF);
  }

  @Test
  void anImageIsNotScannedForPdfNames() {
    byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, '/', 'J', 'S', ' ');

    assertThat(VoucherFile.typeOf(png)).contains(VoucherType.PNG);
  }
}
