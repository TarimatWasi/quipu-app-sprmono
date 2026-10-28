package com.tarimatwasi.quipu.shared.paging;

import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import java.util.UUID;

/**
 * TAR-227: a cursor that carries only the id of the last item of a page, masked in the session that
 * asked for it: never the internal id nor its sort key, which the listing reads again from the row.
 * A cursor of another session, of another kind or altered is an invalid cursor, as is one whose row
 * is no longer there or is outside the listing (the listing checks that when it reads the key).
 */
public final class MaskedCursor {

  private MaskedCursor() {}

  /**
   * The cursor that stands after the item with this id.
   *
   * @throws com.tarimatwasi.quipu.shared.masking.NoMaskSessionException if there is no session
   */
  public static String of(IdMasker masker, IdKind kind, long id) {
    return CursorToken.encode(masker.mask(kind, id).toString());
  }

  /**
   * The internal id of the item the cursor stands after.
   *
   * @throws InvalidCursorException if it is not a cursor of this kind issued in this session
   * @throws com.tarimatwasi.quipu.shared.masking.NoMaskSessionException if there is no session
   */
  public static long idOf(IdMasker masker, IdKind kind, String cursor) {
    String masked = CursorToken.decode(cursor, 1).get(0);
    try {
      UUID uuid = UUID.fromString(masked);
      // UUID.fromString takes short spellings too: only the one this class issues is a cursor.
      if (!uuid.toString().equals(masked)) {
        throw new InvalidCursorException();
      }
      return masker.unmask(kind, uuid);
    } catch (IllegalArgumentException | UnknownMaskedIdException notAnId) {
      throw new InvalidCursorException();
    }
  }
}
