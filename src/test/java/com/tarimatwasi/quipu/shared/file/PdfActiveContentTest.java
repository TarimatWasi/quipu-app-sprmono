package com.tarimatwasi.quipu.shared.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** TAR-39 (D-52): PDFBox reads every object of the file; what a viewer could run is refused. */
class PdfActiveContentTest {

  /** Whether the writer packs the dictionaries into compressed object streams. */
  enum Compression {
    NONE,
    OBJECT_STREAMS;

    CompressParameters parameters() {
      return this == NONE
          ? CompressParameters.NO_COMPRESSION
          : CompressParameters.DEFAULT_COMPRESSION;
    }
  }

  private static final COSName AA = COSName.getPDFName("AA");

  private static PDDocument onePage() throws IOException {
    PDDocument document = new PDDocument();
    PDPage page = new PDPage();
    document.addPage(page);
    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
      content.addRect(72, 72, 100, 100);
      content.fill();
    }
    return document;
  }

  private static byte[] save(PDDocument document, Compression compression) throws IOException {
    try (document) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out, compression.parameters());
      return out.toByteArray();
    }
  }

  private static boolean found(Compression compression, Consumer<PDDocument> change)
      throws IOException {
    PDDocument document = onePage();
    change.accept(document);
    return PdfActiveContent.found(save(document, compression));
  }

  private static PDPage firstPage(PDDocument document) {
    return document.getPage(0);
  }

  private static void annotate(PDDocument document, COSDictionary annotation) {
    try {
      firstPage(document).getAnnotations().add(PDAnnotation.createAnnotation(annotation));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PDAnnotationLink link(PDDocument document) {
    PDAnnotationLink link = new PDAnnotationLink();
    link.setRectangle(new PDRectangle(72, 72, 100, 20));
    try {
      firstPage(document).getAnnotations().add(link);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return link;
  }

  private static COSDictionary javaScript() {
    return new PDActionJavaScript("app.alert(1)").getCOSObject();
  }

  private static boolean contains(byte[] pdf, String text) {
    return new String(pdf, StandardCharsets.ISO_8859_1).contains(text);
  }

  // ---- accepted

  @ParameterizedTest
  @EnumSource(Compression.class)
  void aPlainPdfIsAccepted(Compression compression) throws IOException {
    assertThat(found(compression, document -> {})).isFalse();
    assertThat(PdfActiveContent.found(TestPdfs.plain())).isFalse();
  }

  @Test
  void theWriterReallyPacksTheObjectsIntoACompressedObjectStream() throws IOException {
    byte[] pdf = save(onePage(), Compression.OBJECT_STREAMS);

    assertThat(contains(pdf, "/ObjStm")).isTrue();
    assertThat(contains(pdf, "/MediaBox")).as("the page lives in the object stream").isFalse();
    assertThat(PdfActiveContent.found(pdf)).isFalse();
  }

  @Test
  void largeRandomImagesAreAccepted() throws IOException {
    // The byte scan refused 2-3 % of these: random data holds names such as /JS by chance.
    Random random = new Random(52);
    PDDocument document = onePage();
    for (int i = 0; i < 4; i++) {
      BufferedImage image = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          image.setRGB(x, y, random.nextInt());
        }
      }
      PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.drawImage(xObject, 0, 0, 300, 300);
      }
    }
    byte[] pdf = save(document, Compression.OBJECT_STREAMS);

    assertThat(pdf.length).isGreaterThan(4 * 1024 * 1024);
    assertThat(PdfActiveContent.found(pdf)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void aPdfWithOnlyAnOwnerPasswordIsReadAndAccepted(Compression compression) throws IOException {
    PDDocument document = onePage();
    StandardProtectionPolicy policy =
        new StandardProtectionPolicy("owner-secret", "", new AccessPermission());
    policy.setEncryptionKeyLength(256);
    document.protect(policy);

    assertThat(PdfActiveContent.found(save(document, compression))).isFalse();
  }

  @Test
  void aPdfWithOnlyAnOwnerPasswordIsStillInspected() throws IOException {
    PDDocument document = onePage();
    document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)"));
    document.protect(new StandardProtectionPolicy("owner-secret", "", new AccessPermission()));

    assertThat(PdfActiveContent.found(save(document, Compression.OBJECT_STREAMS))).isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void aLinkToAnAddressIsAccepted(Compression compression) throws IOException {
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://example.com/comprobante");

    assertThat(found(compression, document -> link(document).setAction(uri))).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void anOpenActionThatGoesToAPageIsAccepted(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  PDPageFitDestination destination = new PDPageFitDestination();
                  destination.setPage(firstPage(document));
                  document.getDocumentCatalog().setOpenAction(destination);
                }))
        .isFalse();
    assertThat(
            found(
                compression,
                document -> {
                  PDPageFitDestination destination = new PDPageFitDestination();
                  destination.setPage(firstPage(document));
                  PDActionGoTo goTo = new PDActionGoTo();
                  goTo.setDestination(destination);
                  document.getDocumentCatalog().setOpenAction(goTo);
                }))
        .isFalse();
  }

  // ---- refused

  @ParameterizedTest
  @EnumSource(Compression.class)
  void anOpenActionWithJavaScriptIsRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document ->
                    document
                        .getDocumentCatalog()
                        .setOpenAction(new PDActionJavaScript("app.alert(1)"))))
        .isTrue();
    assertThat(PdfActiveContent.found(TestPdfs.withJavaScript())).isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void anOpenActionThatIsNotAGoToIsRefused(Compression compression) throws IOException {
    PDActionURI uri = new PDActionURI();
    uri.setURI("https://example.com");

    assertThat(found(compression, document -> document.getDocumentCatalog().setOpenAction(uri)))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void additionalActionsOnAPageAreRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  COSDictionary actions = new COSDictionary();
                  actions.setItem(COSName.O, javaScript());
                  firstPage(document).getCOSObject().setItem(AA, actions);
                }))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void additionalActionsOnAnAnnotationAreRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  COSDictionary annotation = new COSDictionary();
                  annotation.setItem(COSName.SUBTYPE, COSName.getPDFName("Link"));
                  annotation.setItem(COSName.RECT, new PDRectangle(1, 1, 5, 5).getCOSArray());
                  annotation.setItem(AA, new COSDictionary());
                  annotate(document, annotation);
                }))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void javaScriptInTheNameTreeIsRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  COSArray names = new COSArray();
                  names.add(new COSString("start"));
                  names.add(javaScript());
                  COSDictionary tree = new COSDictionary();
                  tree.setItem(COSName.NAMES, names);
                  COSDictionary catalogNames = new COSDictionary();
                  catalogNames.setItem(COSName.JAVA_SCRIPT, tree);
                  document.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, catalogNames);
                }))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void anEmbeddedFileIsRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  // a file specification with /EF and no /Type, in the name tree of the catalog
                  COSDictionary embedded = new COSDictionary();
                  embedded.setItem(COSName.F, document.getDocument().createCOSStream());
                  COSDictionary spec = new COSDictionary();
                  spec.setString(COSName.F, "factura.exe");
                  spec.setItem(COSName.EF, embedded);
                  COSArray names = new COSArray();
                  names.add(new COSString("factura.exe"));
                  names.add(spec);
                  COSDictionary tree = new COSDictionary();
                  tree.setItem(COSName.NAMES, names);
                  COSDictionary catalogNames = new COSDictionary();
                  catalogNames.setItem(COSName.EMBEDDED_FILES, tree);
                  document.getDocumentCatalog().getCOSObject().setItem(COSName.NAMES, catalogNames);
                }))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void aFileAttachmentAnnotationIsRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  PDAnnotationFileAttachment attachment = new PDAnnotationFileAttachment();
                  attachment.setRectangle(new PDRectangle(72, 72, 20, 20));
                  try {
                    firstPage(document).getAnnotations().add(attachment);
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  }
                }))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void aLaunchActionIsRefused(Compression compression) throws IOException {
    assertThat(found(compression, document -> link(document).setAction(new PDActionLaunch())))
        .isTrue();
  }

  @ParameterizedTest
  @EnumSource(Compression.class)
  void anXfaFormIsRefused(Compression compression) throws IOException {
    assertThat(
            found(
                compression,
                document -> {
                  COSDictionary form = new COSDictionary();
                  form.setItem(COSName.FIELDS, new COSArray());
                  form.setItem(COSName.XFA, document.getDocument().createCOSStream());
                  document.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
                }))
        .isTrue();
  }

  @Test
  void javaScriptInsideACompressedObjectStreamIsRefused() throws IOException {
    PDDocument document = onePage();
    COSDictionary action = javaScript();
    action.setDirect(false);
    document.getDocumentCatalog().getCOSObject().setItem(COSName.OPEN_ACTION, action);
    byte[] pdf = save(document, Compression.OBJECT_STREAMS);

    assertThat(contains(pdf, "/ObjStm")).isTrue();
    assertThat(contains(pdf, "JavaScript")).as("only inside the compressed stream").isFalse();
    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void aPasswordToOpenTheFileIsRefused() throws IOException {
    PDDocument document = onePage();
    document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));

    assertThat(PdfActiveContent.found(save(document, Compression.OBJECT_STREAMS))).isTrue();
  }

  @Test
  void aTruncatedPdfOrGarbageAfterTheHeaderIsRefused() throws IOException {
    byte[] pdf = save(onePage(), Compression.OBJECT_STREAMS);
    byte[] garbage = new byte[64 * 1024];
    new Random(7).nextBytes(garbage);
    System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, garbage, 0, 9);

    assertThat(PdfActiveContent.found(Arrays.copyOf(pdf, pdf.length / 2))).isTrue();
    assertThat(PdfActiveContent.found(garbage)).isTrue();
    assertThat(PdfActiveContent.found("%PDF-1.7\n%fake".getBytes(StandardCharsets.US_ASCII)))
        .isTrue();
  }

  // ---- files written by hand, to place objects where no writer would

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] deflate(byte[] data) {
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[64 * 1024];
    while (!deflater.finished()) {
      out.write(buffer, 0, deflater.deflate(buffer));
    }
    deflater.end();
    return out.toByteArray();
  }

  private static byte[] stream(String dictionary, byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ascii(dictionary.replace(">>", " /Length " + data.length + " >>\nstream\n")));
    out.writeBytes(data);
    out.writeBytes(ascii("\nendstream"));
    return out.toByteArray();
  }

  /**
   * Objects 1 (catalog), 2 (pages) and 3 (page), then the given ones from 4 on, with a classic
   * cross-reference table that lists them all.
   */
  private static byte[] handMade(byte[]... extra) {
    List<byte[]> objects = new ArrayList<>();
    objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
    objects.add(ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
    objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"));
    objects.addAll(List.of(extra));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ascii("%PDF-1.7\n"));
    StringBuilder xref = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n");
    xref.append("0000000000 65535 f \n");
    for (int i = 0; i < objects.size(); i++) {
      xref.append(String.format("%010d 00000 n \n", out.size()));
      out.writeBytes(ascii((i + 1) + " 0 obj\n"));
      out.writeBytes(objects.get(i));
      out.writeBytes(ascii("\nendobj\n"));
    }
    int start = out.size();
    out.writeBytes(ascii(xref.toString()));
    out.writeBytes(
        ascii(
            "trailer\n<< /Size "
                + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n"
                + start
                + "\n%%EOF\n"));
    return out.toByteArray();
  }

  @Test
  void theHandMadeFilesAreReadable() {
    assertThat(PdfActiveContent.found(handMade())).isFalse();
    assertThat(PdfActiveContent.found(handMade(ascii("<< /Type /Annot /Subtype /Link >>"))))
        .isFalse();
  }

  @Test
  void anOrphanJavaScriptObjectThatNothingReferencesIsRefused() {
    assertThat(PdfActiveContent.found(handMade(ascii("<< /S /JavaScript /JS (app.alert(1)) >>"))))
        .isTrue();
  }

  @Test
  void aNameWrittenWithEscapesIsNormalizedByTheParser() {
    assertThat(PdfActiveContent.found(handMade(ascii("<< /S /J#61vaScript /J#53 (x) >>"))))
        .isTrue();
    assertThat(PdfActiveContent.found(handMade(ascii("<< /Type /Filespec /#45F << >> >>"))))
        .isTrue();
  }

  @Test
  void javaScriptInAnObjectStreamThatTheTableDoesNotListIsRefused() {
    byte[] objects = ascii("9 0 << /S /JavaScript /JS (app.alert(1)) >>");
    byte[] objectStream =
        stream("<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode >>", deflate(objects));

    assertThat(PdfActiveContent.found(handMade(objectStream))).isTrue();
  }

  @Test
  void anObjectStreamThatInflatesBeyondTheLimitIsRefusedInBoundedTime() {
    byte[] objects = new byte[Math.toIntExact(PdfActiveContent.MAX_INFLATED) + 1024];
    Arrays.fill(objects, (byte) ' ');
    System.arraycopy(ascii("9 0 << >>"), 0, objects, 0, 9);
    byte[] pdf =
        handMade(
            stream("<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode >>", deflate(objects)));

    assertThat(pdf.length).isLessThan(1024 * 1024);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10), () -> assertThat(PdfActiveContent.found(pdf)).isTrue());
  }

  @Test
  void anObjectStreamWithAFilterThatIsNotFlateIsRefused() {
    byte[] objectStream =
        stream("<< /Type /ObjStm /N 1 /First 4 /Filter /ASCIIHexDecode >>", ascii("39203020"));

    assertThat(PdfActiveContent.found(handMade(objectStream))).isTrue();
  }
}
