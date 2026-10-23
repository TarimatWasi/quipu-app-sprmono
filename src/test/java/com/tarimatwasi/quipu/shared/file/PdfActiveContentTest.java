package com.tarimatwasi.quipu.shared.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TAR-39: the scan fails closed; what a viewer could run must not hide from it. */
class PdfActiveContentTest {

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater();
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    return out.toByteArray();
  }

  /** A stream object with that dictionary and that (already encoded) body. */
  private static byte[] pdfWithStream(String dictionary, byte[] body) {
    return concat(
        ascii("%PDF-1.7\n1 0 obj\n" + dictionary + "\nstream\n"),
        body,
        ascii("\nendstream\nendobj\n%%EOF"));
  }

  private static boolean found(String body) {
    return PdfActiveContent.found(ascii("%PDF-1.7\n" + body + "\n%%EOF"));
  }

  @Test
  void anOpenActionInsideACompressedObjectStreamIsFound() {
    byte[] objects = ascii("2 0 << /Type /Catalog /OpenAction 3 0 R >>");
    byte[] pdf =
        pdfWithStream("<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode >>", deflate(objects));

    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void aLooseStreamKeywordDoesNotHideTheObjectsAfterIt() {
    assertThat(
            found(
                "9 0 obj << >> stream\n1 0 obj << /Type /Catalog /OpenAction 2 0 R >> endobj\n"
                    + "endstream"))
        .isTrue();
  }

  @Test
  void anUnclosedParenthesisDoesNotHideTheNextObject() {
    assertThat(
            found(
                "1 0 obj << /T (never closed >> endobj\n"
                    + "2 0 obj << /Type /Catalog /OpenAction 3 0 R >> endobj"))
        .isTrue();
  }

  @Test
  void anEmbeddedFileWithoutTypeIsFound() {
    assertThat(found("<< /Type /Filespec /F (a.exe) /EF << /F 5 0 R >> >>")).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "XFA",
        "RichMedia",
        "SubmitForm",
        "ImportData",
        "GoToE",
        "FileAttachment",
        "EF",
        "EmbeddedFiles"
      })
  void theOtherActiveEntriesAreFound(String name) {
    assertThat(found("<< /" + name + " 5 0 R >>")).as(name).isTrue();
    assertThat(found("<< /S /" + name + " >>")).as(name).isTrue();
  }

  @Test
  void aNameInAStringACommentOrARawStreamIsRefusedBecauseTheScanFailsClosed() {
    assertThat(found("<< /T (/JS) >>")).isTrue();
    assertThat(found("% about /OpenAction")).isTrue();
    assertThat(found("<< /Length 12 >>\nstream\nbinary /JS /AA\nendstream")).isTrue();
    assertThat(found("<< /Font << /JS 2 0 R >> >>")).isTrue();
  }

  @Test
  void aPlainPdfWithCompressedContentIsAccepted() {
    byte[] content = ascii("q 1 0 0 1 0 0 cm BT /F1 12 Tf (Pago por JavaScript) Tj ET Q");
    byte[] pdf =
        concat(
            ascii("%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"),
            ascii("2 0 obj << /Type /Page /Resources << /Font << /F1 4 0 R >> >> >> endobj\n"),
            pdfWithStream("<< /Length 99 /Filter /FlateDecode >>", deflate(content)));

    assertThat(PdfActiveContent.found(pdf)).isFalse();
    assertThat(found("<< /JSON 1 /AAPL 2 /Launcher 3 /EFX 4 >>")).isFalse();
  }

  @Test
  void aNameCutBetweenTwoChunksOfTheInflatedStreamIsStillFound() {
    for (int shift = 0; shift < 12; shift++) {
      byte[] text = new byte[PdfActiveContent.CHUNK * 2];
      Arrays.fill(text, (byte) ' ');
      byte[] name = ascii("/OpenAction ");
      System.arraycopy(name, 0, text, PdfActiveContent.CHUNK - shift, name.length);
      byte[] pdf = pdfWithStream("<< /Filter /FlateDecode >>", deflate(text));

      assertThat(PdfActiveContent.found(pdf)).as("shift %d", shift).isTrue();
    }
  }

  @Test
  void aFlateStreamThatDoesNotInflateIsRefused() {
    assertThat(PdfActiveContent.found(pdfWithStream("<< /Filter /FlateDecode >>", ascii("abcd"))))
        .isTrue();
    byte[] cut = Arrays.copyOf(deflate(ascii("q Q ".repeat(1000))), 10);
    assertThat(PdfActiveContent.found(pdfWithStream("<< /Filter /FlateDecode >>", cut))).isTrue();
  }

  @Test
  void aFilterThatCouldHideNamesIsRefused() {
    assertThat(found("<< /Filter /ASCIIHexDecode >>")).isTrue();
    assertThat(found("<< /Filter [/A85 /FlateDecode] >>")).isTrue();
    assertThat(found("<< /Filter /LZWDecode >>")).isTrue();
    assertThat(found("<< /Filter /RunLengthDecode >>")).isTrue();
  }

  @Test
  void anObjectStreamWithAPredictorIsRefused() {
    byte[] pdf =
        pdfWithStream(
            "<< /Type /ObjStm /Filter /FlateDecode /DecodeParms << /Predictor 12 >> >>",
            deflate(ascii("2 0 << /Type /Catalog >>")));

    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void aDecompressionBombIsRefusedInBoundedTime() {
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    byte[] zeros = new byte[1 << 20];
    ByteArrayOutputStream bomb = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    for (long written = 0; written < PdfActiveContent.MAX_INFLATED * 2L; written += zeros.length) {
      deflater.setInput(zeros);
      while (!deflater.needsInput()) {
        bomb.write(buffer, 0, deflater.deflate(buffer));
      }
    }
    deflater.finish();
    while (!deflater.finished()) {
      bomb.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    byte[] pdf = pdfWithStream("<< /Filter /FlateDecode >>", bomb.toByteArray());

    assertThat(pdf.length).isLessThan(1 << 20);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5), () -> assertThat(PdfActiveContent.found(pdf)).isTrue());
  }
}
