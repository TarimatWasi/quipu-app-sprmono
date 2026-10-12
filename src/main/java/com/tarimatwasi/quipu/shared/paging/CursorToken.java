package com.tarimatwasi.quipu.shared.paging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
      parts.add(text(piece));
    }
    return parts;
  }

  /**
   * One part as text. It must be valid UTF-8 and have no NUL: PostgreSQL cannot store a NUL in
   * text, so such a value would reach the query and fail there as a 500 instead of a 400.
   */
  private static String text(String piece) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(DECODER.decode(piece)))
              .toString();
      if (text.indexOf('\u0000') >= 0) {
        throw new InvalidCursorException();
      }
      return text;
    } catch (IllegalArgumentException | CharacterCodingException notText) {
      throw new InvalidCursorException();
    }
  }
}
