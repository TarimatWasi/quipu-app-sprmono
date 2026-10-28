package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
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
    assertThat(VoucherFile.typeOf(TestPdfs.plain())).contains(VoucherType.PDF);
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
    byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

    assertThat(VoucherFile.typeOf(padded(png, VoucherFile.MAX_BYTES))).contains(VoucherType.PNG);
    assertThat(VoucherFile.typeOf(padded(png, VoucherFile.MAX_BYTES + 1))).isEmpty();
    assertThat(VoucherFile.typeOf(TestPdfs.ofSize(VoucherFile.MAX_BYTES)))
        .contains(VoucherType.PDF);
    assertThat(VoucherFile.MAX_BYTES).isEqualTo(5 * 1024 * 1024);
  }

  @Test
  void everyTypeHasADistinctContentTypeAndExtension() {
    assertThat(Arrays.stream(VoucherType.values()).map(VoucherType::contentType).distinct())
        .hasSize(3);
    assertThat(Arrays.stream(VoucherType.values()).map(VoucherType::extension).distinct())
        .hasSize(3);
  }

  @Test
  void aPdfIsReadForActiveContent() {
    // TAR-39 (D-52): the detection itself is tested in PdfActiveContentTest.
    assertThat(VoucherFile.typeOf(TestPdfs.plain())).contains(VoucherType.PDF);
    assertThat(VoucherFile.typeOf(TestPdfs.withJavaScript())).isEmpty();
    assertThat(VoucherFile.typeOf("%PDF-1.7\n%fake".getBytes(StandardCharsets.US_ASCII))).isEmpty();
  }

  @Test
  void anImageIsNotScannedForPdfNames() {
    byte[] png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, '/', 'J', 'S', ' ');

    assertThat(VoucherFile.typeOf(png)).contains(VoucherType.PNG);
  }
}
