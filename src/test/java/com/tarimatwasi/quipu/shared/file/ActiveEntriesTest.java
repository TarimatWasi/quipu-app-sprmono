package com.tarimatwasi.quipu.shared.file;

import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.ascii;
import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.handMade;
import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.handMadeDocument;
import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.handMadePage;
import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.objects;
import static com.tarimatwasi.quipu.shared.file.HandMadePdfs.stream;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * TAR-39 (D-52): each dictionary is judged by what it holds, never by the key that led to it; the
 * files are written by hand to place objects where no writer would.
 */
class ActiveEntriesTest {

  private static final byte[] FONT =
      ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  private static final byte[] FORM =
      stream("<< /Type /XObject /Subtype /Form /BBox [0 0 1 1] >>", ascii("0 0 m"));

  private static boolean pageFound(String pageEntries, byte[]... extra) {
    return PdfActiveContent.found(handMadePage(pageEntries, extra));
  }

  @Test
  void anActiveKeyNextToAResourceDictionaryIsStillFound() {
    assertThat(pageFound("/Resources << /Font << /F1 4 0 R >> >> /JS (x)", FONT))
        .as("/JS beside the resources")
        .isTrue();
    assertThat(pageFound("/Resources << /Font << /F1 4 0 R >> /JS (x) >>", FONT))
        .as("/JS in the resources, beside the maps")
        .isTrue();
    assertThat(
            pageFound("/Resources << /Font << /F1 4 0 R >> >>", ascii("<< /Type /Font /JS (x) >>")))
        .as("/JS in the font that the map points to")
        .isTrue();
    assertThat(
            PdfActiveContent.found(
                handMade(ascii("<< /Font << /F1 4 0 R >> /OpenAction << /S /JavaScript >> >>"))))
        .as("a map of fonts that is not inside a /Resources")
        .isTrue();
  }

  @Test
  void anActionThatIsAlsoReachedFromAResourceMapIsStillRefused() {
    byte[] action = ascii("<< /S /JavaScript /JS (app.alert(1)) >>");

    assertThat(pageFound("/Resources << /Font << /F1 4 0 R >> >>", action))
        .as("an action used as a font")
        .isTrue();
    assertThat(pageFound("/Resources << /Font 4 0 R >>", action))
        .as("an action used as the map of fonts")
        .isTrue();
    assertThat(
            pageFound(
                "/Resources << /XObject << /X1 4 0 R >> >>",
                stream(
                    "<< /Type /XObject /Subtype /Form /BBox [0 0 1 1] /AA << /O 5 0 R >> >>",
                    ascii("0 0 m")),
                action))
        .as("additional actions on a form XObject")
        .isTrue();
  }

  @Test
  void theCatalogIsNeverAResourceMap() {
    // the catalog, also used as the map of fonts, opens with an action that is not a go-to
    byte[] uri = ascii("<< /S /URI /URI (https://example.com) >>");

    assertThat(PdfActiveContent.found(handMadeDocument("", "/Resources << /Font 1 0 R >>", uri)))
        .as("control: the same file without the action on open")
        .isFalse();
    assertThat(
            PdfActiveContent.found(
                handMadeDocument("/OpenAction 4 0 R", "/Resources << /Font 1 0 R >>", uri)))
        .isTrue();
  }

  @Test
  void aDictionaryAlsoReachedAsAResourceMapIsStillInspected() {
    // MEDIUM 2: the form and the names of the catalog, also used as the map of fonts of the page
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /AcroForm 4 0 R",
                    "/Resources << /Font 4 0 R >>",
                    ascii("<< /Fields [] /XFA 5 0 R >>"),
                    FORM)))
        .as("an AcroForm with /XFA")
        .isTrue();
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /Names 4 0 R",
                    "/Resources << /Font 4 0 R >>",
                    ascii("<< /EmbeddedFiles 5 0 R >>"),
                    ascii("<< /Names [] >>"))))
        .as("a name dictionary with /EmbeddedFiles")
        .isTrue();
  }

  /** A page with one annotation (object 4) and more objects from 5 on. */
  private static boolean annotationFound(String annotation, byte[]... more) {
    byte[][] objects = new byte[more.length + 1][];
    objects[0] = ascii("<< /Type /Annot /Rect [0 0 1 1] " + annotation + " >>");
    System.arraycopy(more, 0, objects, 1, more.length);
    return PdfActiveContent.found(handMadePage("/Annots [4 0 R]", objects));
  }

  @Test
  void onlyGoToAndLinkActionsAreAccepted() {
    // MEDIUM 4: a whitelist of /S in every action
    assertThat(annotationFound("/Subtype /Link /A << /S /GoTo /D [3 0 R /Fit] >>")).isFalse();
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (https://a.b) >>")).isFalse();
    assertThat(annotationFound("/Subtype /Link /A << /S /JavaScript /JS (x) >>"))
        .as("JavaScript in the /A of an annotation")
        .isTrue();
    assertThat(annotationFound("/Subtype /Link /A << /S /Named /N /Print >>"))
        .as("/Named")
        .isTrue();
    assertThat(annotationFound("/Subtype /Link /A << /S /Thread /D 1 0 R >>"))
        .as("/Thread")
        .isTrue();
    assertThat(
            annotationFound(
                "/Subtype /Link /A << /S /GoTo /D [3 0 R /Fit] /Next << /S /Named /N /Print >> >>"))
        .as("a /Next chained to a go-to")
        .isTrue();
    assertThat(
            annotationFound(
                "/Subtype /Link /A << /S /GoTo /D [3 0 R /Fit]"
                    + " /Next [<< /S /URI /URI (https://a.b) >> << /S /Hide /T (x) >>] >>"))
        .as("an array of /Next")
        .isTrue();
    assertThat(PdfActiveContent.found(handMade(ascii("<< /Type /Action /S /SetOCGState >>"))))
        .as("any /Type /Action")
        .isTrue();
  }

  @Test
  void mediaAnd3dAreRefused() {
    for (String subtype : new String[] {"3D", "Movie", "Sound", "Screen", "RichMedia"}) {
      assertThat(annotationFound("/Subtype /" + subtype)).as(subtype).isTrue();
    }
    assertThat(PdfActiveContent.found(handMade(ascii("<< /OnInstantiate 5 0 R >>"))))
        .as("/OnInstantiate")
        .isTrue();
  }

  @Test
  void theAttributesOfATaggedPdfAreNotActions() {
    // the /A of a structure element holds attributes, with no /S
    assertThat(
            PdfActiveContent.found(
                handMade(
                    ascii("<< /Type /StructElem /S /P /A << /O /Layout /Placement /Block >> >>"))))
        .isFalse();
  }

  // ---- judged by value, not by position (review of 333730c)

  @Test
  void aPageWithoutTypeReachedThroughIndirectKidsIsStillInspected() {
    // the page, with no /Type, is also the map of fonts of itself; its kids are an indirect array
    byte[] pdf =
        objects(
            ascii("<< /Type /Catalog /Pages 2 0 R >>"),
            ascii("<< /Type /Pages /Kids 4 0 R /Count 1 >>"),
            ascii(
                "<< /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font 3 0 R >>"
                    + " /AA << /O 5 0 R >> >>"),
            ascii("[3 0 R]"),
            ascii("<< /S /GoTo /D [3 0 R /Fit] >>"));

    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void anIndirectArrayOfNextActionsIsJudgedByItsContent() {
    assertThat(
            annotationFound(
                "/Subtype /Link /A << /S /GoTo /D [3 0 R /Fit] /Next 5 0 R >>",
                ascii("[6 0 R]"),
                ascii("<< /S /Named /N /Print >>")))
        .isTrue();
    assertThat(
            annotationFound(
                "/Subtype /Link /A << /S /GoTo /D [3 0 R /Fit] /Next 5 0 R >>",
                ascii("[6 0 R]"),
                ascii("<< /S /URI /URI (https://example.com) >>")))
        .as("control: a chain of a go-to and a link")
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"O", "C", "K"})
  void anyAdditionalActionIsRefused(String key) {
    assertThat(
            PdfActiveContent.found(
                handMadePage(
                    "/AA << /" + key + " 4 0 R >>", ascii("<< /S /GoTo /D [3 0 R /Fit] >>"))))
        .isTrue();
  }

  @Test
  void aTaggedPdfIsAccepted() {
    // the /S of a structure element is its role, not an action
    byte[] pdf =
        objects(
            ascii(
                "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 4 0 R /MarkInfo << /Marked true >>"
                    + " >>"),
            ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /StructParents 0 >>"),
            ascii("<< /Type /StructTreeRoot /K 5 0 R /RoleMap << /Title /H1 >> >>"),
            ascii("<< /Type /StructElem /S /Document /P 4 0 R /K [6 0 R] >>"),
            ascii("<< /Type /StructElem /S /P /P 5 0 R /Pg 3 0 R /K [7 0 R] >>"),
            ascii("<< /S /Span /P 6 0 R /Pg 3 0 R /K 0 /A << /O /Layout /Placement /Inline >> >>"));

    assertThat(PdfActiveContent.found(pdf)).isFalse();
  }

  @Test
  void dictionariesThatUseAnSForOtherThingsAreNotActions() {
    byte[] pdf =
        handMadeDocument(
            "/Type /Catalog /PageLabels << /Nums [0 << /S /D /St 1 >>] >>",
            "/Group << /Type /Group /S /Transparency /CS /DeviceRGB >>"
                + " /Resources << /ExtGState << /G1 4 0 R >> >> /Annots [5 0 R]",
            ascii("<< /Type /ExtGState /SMask << /Type /Mask /S /Luminosity /G 6 0 R >> >>"),
            ascii(
                "<< /Type /Annot /Subtype /Link /Rect [0 0 1 1] /BS << /W 1 /S /D >>"
                    + " /BE << /S /C >> /A << /S /GoTo /D [3 0 R /Fit] >> >>"),
            stream("<< /Type /XObject /Subtype /Form /BBox [0 0 1 1] >>", ascii("0 0 m")));

    assertThat(PdfActiveContent.found(pdf)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"https://example.com/pago", "HTTP://example.com", "mailto:pagos@example.com"})
  void aLinkToTheWebOrToAnEmailIsAccepted(String uri) {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (" + uri + ") >>")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:app.alert(1)",
        "JavaScript:app.alert(1)",
        " javascript:x",
        "java\\tscript:x",
        "\\001javascript:x",
        "file:///C:/Windows/System32/calc.exe",
        "data:text/html,x",
        "relative/path",
        ""
      })
  void aLinkToAnythingElseIsRefused(String uri) {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (" + uri + ") >>"))
        .as(uri)
        .isTrue();
  }

  @Test
  void aLinkThatIsNotAStringIsRefused() {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI /https >>")).isTrue();
  }

  @Test
  void aScriptOrAnXfaFormStoredAsAResourceIsStillRefused() {
    // a dictionary of streams only looks like a map of XObjects; /JS and /XFA can be streams too
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /AcroForm 4 0 R", "", ascii("<< /XFA 5 0 R >>"), FORM)))
        .as("an XFA form with no other entry")
        .isTrue();
    assertThat(pageFound("/Resources << /XObject << /JS 4 0 R >> >>", FORM))
        .as("an XObject named /JS")
        .isTrue();
  }

  // ---- every /S that is not a structure element is an action (TAR-39, by value)

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/S /Launch /F (calc.exe)",
        "/S /JavaScript /JS (app.alert(1))",
        "/S /SubmitForm /F (https://a.b)",
        "/S /ImportData /F (x.fdf)",
        "/S /GoToE /T << /R /C /N (x) >>",
        "/S /RichMediaExecute /TI 5 0 R",
        "/S /AcmeRun /P 5 0 R",
        "/Type /StructElem /S /Launch /P 5 0 R /K 0"
      })
  void anActionOnOpenThatIsNotAGoToOrALinkIsRefused(String action) {
    // a vendor action type, or a known one disguised as a structure element, is refused too
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /OpenAction 4 0 R",
                    "",
                    ascii("<< " + action + " >>"),
                    ascii("<< >>"))))
        .as(action)
        .isTrue();
  }

  @Test
  void anUntypedDictionaryWithAnUnknownSIsAnAction() {
    assertThat(annotationFound("/Subtype /Link /A << /S /AcmeRun >>")).isTrue();
  }

  @Test
  void anEmbeddedFileStreamIsRefusedWhereverItIs() {
    assertThat(
            PdfActiveContent.found(
                handMade(stream("<< /Type /EmbeddedFile /Subtype /text#2Fplain >>", ascii("x")))))
        .isTrue();
  }

  @Test
  void additionalActionsAreRefusedWhateverTheyHold() {
    assertThat(
            PdfActiveContent.found(
                handMadePage("/AA << /O 4 0 R >>", ascii("<< /S /Launch /F (calc.exe) >>"))))
        .isTrue();
  }

  @Test
  void aStructureElementReachedFromTheTreeNeedsNoKids() {
    // object 6 has no /K and no /Type: it is a structure element because the tree reaches it
    byte[] pdf =
        objects(
            ascii("<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 4 0 R >>"),
            ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"),
            ascii("<< /Type /StructTreeRoot /K 5 0 R >>"),
            ascii("<< /S /Document /P 4 0 R /K 7 0 R >>"),
            ascii("<< /S /Figure /P 5 0 R >>"),
            ascii("[6 0 R]"));

    assertThat(PdfActiveContent.found(pdf)).isFalse();
  }

  @Test
  void anOutputIntentOfAPdfaIsNotAnAction() {
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /OutputIntents [<< /Type /OutputIntent /S /GTS_PDFA1"
                        + " /OutputConditionIdentifier (sRGB) >>]",
                    "")))
        .isFalse();
  }

  // ---- review of 9a54771: no exemption ever hides an active key

  @Test
  void anEmbeddedFileBehindFakeFontsIsRefused() {
    // HIGH: /Names -> /EmbeddedFiles X, X and the file specification pretend to be fonts
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /Names 4 0 R",
                    "",
                    ascii("<< /EmbeddedFiles 5 0 R >>"),
                    ascii("<< /Subtype /Font /Names [(x.pdf) 6 0 R] >>"),
                    ascii("<< /EF 7 0 R >>"),
                    ascii("<< /Subtype /Font /F 8 0 R >>"),
                    stream("<< >>", ascii("%PDF-1.7")))))
        .isTrue();
  }

  @Test
  void anOpenActionOfACatalogWithFakeFontsIsRefused() {
    // MEDIUM 1: a catalog with no /Type whose values all say /Subtype /Font
    byte[] pdf =
        objects(
            ascii("<< /Pages 2 0 R /OpenAction 4 0 R >>"),
            ascii("<< /Type /Pages /Subtype /Font /Kids [3 0 R] /Count 1 >>"),
            ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"),
            ascii("<< /S /URI /URI (https://example.com) /Subtype /Font >>"));

    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void additionalActionsOfAPageWithFakeFontsAreRefused() {
    // MEDIUM 1: a page with no /Type and no /MediaBox whose values all say /Subtype /Font
    byte[] pdf =
        objects(
            ascii("<< /Type /Catalog /Pages 2 0 R >>"),
            ascii(
                "<< /Type /Pages /Subtype /Font /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] >>"),
            ascii("<< /Parent 2 0 R /AA 4 0 R >>"),
            ascii("<< /Subtype /Font /O 5 0 R >>"),
            ascii("<< /S /GoTo /D [3 0 R /Fit] /Subtype /Font >>"));

    assertThat(PdfActiveContent.found(pdf)).isTrue();
  }

  @Test
  void aResourceNamedAsAnActiveKeyIsRefused() {
    // the names of resources get no exemption: no writer names a font /JS or an XObject /AA
    assertThat(pageFound("/Resources << /Font << /JS 4 0 R >> >>", FONT)).isTrue();
    assertThat(pageFound("/Resources << /XObject << /AA 5 0 R >> >>", FONT, FORM)).isTrue();
    assertThat(
            pageFound(
                "/Resources << /Font << /F1 4 0 R >> /XObject << /X1 5 0 R >> >>", FONT, FORM))
        .as("control: the usual names")
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {" https://example.com", "https://example.com/a b", "https://ex\001ample.com"})
  void aLinkWithSpacesOrControlCharactersIsRefused(String uri) {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (" + uri + ") >>"))
        .as(uri)
        .isTrue();
  }

  @Test
  void theBaseOfTheLinksMustBeOnTheWeb() {
    assertThat(
            PdfActiveContent.found(
                handMadeDocument("/Type /Catalog /URI << /Base (https://example.com/) >>", "")))
        .isFalse();
    assertThat(
            PdfActiveContent.found(
                handMadeDocument("/Type /Catalog /URI << /Base (file:///C:/) >>", "")))
        .isTrue();
    assertThat(
            PdfActiveContent.found(
                handMadeDocument("/Type /Catalog /URI << /Base (mailto:a@b.c) >>", "")))
        .isTrue();
  }

  // ---- review of 489aaf3

  @Test
  void anIndirectArrayOfNextActionsOnOpenIsRefused() {
    // regression of 333730c: /OpenAction go-to whose /Next is an indirect array with /Named
    assertThat(
            PdfActiveContent.found(
                handMadeDocument(
                    "/Type /Catalog /OpenAction << /S /GoTo /D [3 0 R /Fit] /Next 4 0 R >>",
                    "",
                    ascii("[<< /S /Named /N /Print >>]"))))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mailto:pagos@example.com?attach=C:/secret.txt",
        "mailto:pagos@example.com?subject=x",
        "mailto:pagos@example.com&attachment=x",
        "MAILTO:a@b.c?Attach=x",
        "mailto:a@b.c%3Fattach=C:/x",
        "mailto:a@b.c%26attachment=C:/x"
      })
  void anEmailLinkWithParametersIsRefused(String uri) {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (" + uri + ") >>"))
        .as(uri)
        .isTrue();
  }

  @Test
  void aWebLinkMayHaveAQuery() {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (https://a.b/p?x=1&y=2) >>"))
        .isFalse();
  }

  @Test
  void aPlainEmailLinkIsAccepted() {
    assertThat(annotationFound("/Subtype /Link /A << /S /URI /URI (mailto:pagos@example.com) >>"))
        .isFalse();
  }
}
