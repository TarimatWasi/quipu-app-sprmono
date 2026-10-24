package com.tarimatwasi.quipu.shared.file;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.jspecify.annotations.Nullable;

/**
 * Whether one dictionary of a PDF is active, judged by what it holds and never by the key that led
 * to it (TAR-39, D-52): {@link PdfActiveContent} visits every dictionary of the file, so a
 * dictionary reachable in two ways is judged once, by its own content.
 *
 * <ul>
 *   <li>An <strong>action</strong> is a dictionary that says {@code /Type /Action} or has a name in
 *       {@code /S}, unless that name is no action type of the PDF specification and the dictionary
 *       is a structure element ({@code /Type /StructElem}, both {@code /P} and {@code /K}, or
 *       reached through {@code /K} from the {@code /StructTreeRoot}), says another {@code /Type} (a
 *       group, a soft mask, an output intent), or is a known style ({@link #NOT_ACTIONS}). Only
 *       {@code /GoTo} and {@code /URI} are accepted, and a {@code /URI} only to {@code http:},
 *       {@code https:} or {@code mailto:}: any other name, a vendor's too, is refused.
 *   <li>Every dictionary is active if it has a key of {@link #ACTIVE_KEYS} (so every {@code /AA},
 *       whatever actions it holds), an {@code /OpenAction} that is not a destination or a {@code
 *       /GoTo}, or a {@code /Base} of the links that is not on the web. No dictionary is exempt:
 *       not even a map of resources, whose names the producer chooses, since a writer never names a
 *       font {@code /JS} and a dictionary can always pretend to be such a map.
 * </ul>
 */
final class ActiveEntries {

  private static final COSName S = name("S");
  private static final COSName TYPE = name("Type");
  private static final COSName SUBTYPE = name("Subtype");
  private static final COSName OPEN_ACTION = name("OpenAction");
  private static final COSName GO_TO = name("GoTo");
  private static final COSName URI = name("URI");
  private static final COSName BASE = name("Base");
  private static final COSName ACTION = name("Action");
  private static final COSName STRUCT_ELEM = name("StructElem");
  private static final COSName PARENT = name("P");
  private static final COSName KIDS = name("K");

  /**
   * Values of {@code /S} of untyped dictionaries that are no action: the style of a border, of its
   * effect and of a page label, and the subtype of a transparency group or a soft mask.
   */
  static final Set<COSName> NOT_ACTIONS =
      names(
          "S", "D", "B", "I", "U", "C", "R", "r", "A", "a", "Transparency", "Luminosity", "Alpha");

  /** Keys that are active whatever their value. */
  static final List<COSName> ACTIVE_KEYS =
      List.of(
          name("JS"),
          name("JavaScript"),
          name("EF"),
          name("EmbeddedFiles"),
          name("XFA"),
          name("RichMedia"),
          name("AA"),
          name("OnInstantiate"));

  /** The action types of the PDF specification (2.0, and the obsolete ones of 1.2). */
  private static final Set<COSName> ACTION_TYPES =
      names(
          "GoTo",
          "GoToR",
          "GoToE",
          "GoToDp",
          "Launch",
          "Thread",
          "URI",
          "Sound",
          "Movie",
          "Hide",
          "Named",
          "SubmitForm",
          "ResetForm",
          "ImportData",
          "SetOCGState",
          "Rendition",
          "Trans",
          "GoTo3DView",
          "JavaScript",
          "RichMediaExecute",
          "NOP",
          "SetState");

  /** The only action types an action may have: go to a place of the document, or open a link. */
  private static final Set<COSName> ALLOWED_ACTIONS = Set.of(GO_TO, URI);

  /** The schemes a link may open. */
  private static final List<String> ALLOWED_SCHEMES = List.of("http:", "https:", "mailto:");

  /** The schemes a base of the links may have. */
  private static final List<String> WEB_SCHEMES = List.of("http:", "https:");

  /** Values of {@code /Type} or {@code /Subtype} that carry a file, media or 3D. */
  private static final Set<COSName> ACTIVE_TYPES =
      names("EmbeddedFile", "FileAttachment", "RichMedia", "3D", "Movie", "Sound", "Screen");

  private ActiveEntries() {}

  private static COSName name(String name) {
    return COSName.getPDFName(name);
  }

  private static Set<COSName> names(String... names) {
    return Set.of(java.util.Arrays.stream(names).map(ActiveEntries::name).toArray(COSName[]::new));
  }

  /**
   * Whether the dictionary makes the file run, open or carry something.
   *
   * @param structure the structure elements reached through {@code /K} from the tree
   */
  static boolean isActive(COSDictionary dictionary, Set<COSDictionary> structure) {
    COSName s = dictionary.getCOSName(S);
    COSName type = dictionary.getCOSName(TYPE);
    if (in(ACTIVE_TYPES, type) || in(ACTIVE_TYPES, dictionary.getCOSName(SUBTYPE))) {
      return true;
    }
    if (ACTION.equals(type) || (s != null && isAction(dictionary, s, type, structure))) {
      if (!in(ALLOWED_ACTIONS, s)) {
        return true;
      }
      if (URI.equals(s) && !isAllowedLink(dictionary.getDictionaryObject(URI))) {
        return true;
      }
    }
    if (dictionary.containsKey(BASE) && !isWebLink(dictionary.getDictionaryObject(BASE))) {
      return true;
    }
    for (COSName key : ACTIVE_KEYS) {
      if (dictionary.containsKey(key)) {
        return true;
      }
    }
    COSBase open = dictionary.getDictionaryObject(OPEN_ACTION);
    return open != null && !(open instanceof COSArray) && !isGoTo(open);
  }

  private static boolean isAction(
      COSDictionary dictionary, COSName s, @Nullable COSName type, Set<COSDictionary> structure) {
    if (ACTION_TYPES.contains(s)) {
      return true; // whatever else it says, a viewer may run it
    }
    boolean structureElement =
        STRUCT_ELEM.equals(type)
            || (dictionary.containsKey(PARENT) && dictionary.containsKey(KIDS))
            || structure.contains(dictionary);
    return !structureElement && type == null && !NOT_ACTIONS.contains(s);
  }

  /** A link only to the web or to an e-mail address, with no space or control character. */
  static boolean isAllowedLink(@Nullable COSBase uri) {
    return hasScheme(uri, ALLOWED_SCHEMES);
  }

  /** A base of the links only on the web. */
  private static boolean isWebLink(@Nullable COSBase uri) {
    return hasScheme(uri, WEB_SCHEMES);
  }

  private static boolean hasScheme(@Nullable COSBase uri, List<String> schemes) {
    if (!(uri instanceof COSString text)) {
      return false;
    }
    String link = text.getString();
    if (link.codePoints().anyMatch(c -> c <= ' ' || c == 0x7F || Character.isISOControl(c))) {
      return false;
    }
    String lower = link.toLowerCase(Locale.ROOT);
    return schemes.stream().anyMatch(lower::startsWith);
  }

  private static boolean isGoTo(COSBase action) {
    return action instanceof COSDictionary dictionary && GO_TO.equals(dictionary.getCOSName(S));
  }

  private static boolean in(Set<COSName> names, @Nullable COSName name) {
    return name != null && names.contains(name);
  }
}
