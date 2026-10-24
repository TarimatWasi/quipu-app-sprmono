package com.tarimatwasi.quipu.shared.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

/** Real PDF files for the tests, written with PDFBox: the upload routes parse what they receive. */
public final class TestPdfs {

  private TestPdfs() {}

  /** A one-page PDF with a line of text. */
  public static byte[] plain() {
    return save(page(""));
  }

  /** A one-page PDF that runs JavaScript when it is opened. */
  public static byte[] withJavaScript() {
    PDDocument document = page("");
    document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)"));
    return save(document);
  }

  /** A valid PDF of exactly that many bytes, padded with a comment in an uncompressed page. */
  public static byte[] ofSize(int size) {
    int padding = size;
    byte[] pdf = save(page(" ".repeat(padding)));
    while (pdf.length != size) {
      padding += size - pdf.length;
      pdf = save(page(" ".repeat(padding)));
    }
    return pdf;
  }

  private static PDDocument page(String padding) {
    PDDocument document = new PDDocument();
    PDPage page = new PDPage();
    document.addPage(page);
    try (PDPageContentStream content =
        new PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      content.newLineAtOffset(72, 720);
      content.showText("Pago de octubre");
      content.endText();
      content.addComment(padding);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return document;
  }

  private static byte[] save(PDDocument document) {
    try (document) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
