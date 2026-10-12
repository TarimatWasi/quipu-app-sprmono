package com.tarimatwasi.quipu.shared.paging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Packs the sort key of the last item of a page into the opaque cursor. Every part is Base64Url
 * encoded on its own and the parts are joined with a dot, which Base64Url never produces, so any
 * text is safe as a part.
 *
 * <p>The cursor is a position, not a permission, so it is not signed: anyone may build one, and the
 * worst it does is start the list further on. The paged lists are ADMIN only and an ADMIN can read
 * every row of them anyway. Only a cursor that is malformed (not Base64Url, wrong number of parts,
 * a key that does not parse) is refused.
 */
public final class CursorToken {

  private static final String SEPARATOR = ".";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private CursorToken() {}

  /** The cursor for these key parts. */
  public static String encode(String... parts) {
    List<String> encoded = new ArrayList<>(parts.length);
    for (String part : parts) {
      encoded.add(ENCODER.encodeToString(part.getBytes(StandardCharsets.UTF_8)));
    }
    return String.join(SEPARATOR, encoded);
  }

  /**
   * The key parts of a cursor.
   *
   * @throws InvalidCursorException if it is not a cursor with exactly {@code expectedParts} parts
   */
  public static List<String> decode(String token, int expectedParts) {
    String[] pieces = token.split(Pattern.quote(SEPARATOR), -1);
    if (token.isEmpty() || pieces.length != expectedParts) {
      throw new InvalidCursorException();
    }
    List<String> parts = new ArrayList<>(expectedParts);
    for (String piece : pieces) {
      try {
        parts.add(new String(DECODER.decode(piece), StandardCharsets.UTF_8));
      } catch (IllegalArgumentException notBase64) {
        throw new InvalidCursorException();
      }
    }
    return parts;
  }
}
