package com.tarimatwasi.quipu.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** TAR-227: what a test checks of a page cursor that carries only a masked id. */
public final class MaskedCursors {

  private static final String CANONICAL_UUID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

  private MaskedCursors() {}

  /**
   * Checks that the cursor is a single Base64Url part holding only a canonical UUID (so no date nor
   * any other key travels in it) that unmasks, in the current session, to {@code expected}, and
   * that no internal id of the listing shows in it, decoded or not. The ids should be large (as
   * {@link TestIds} gives them), so that none of them is in a UUID by chance.
   *
   * @return the masked id, as the cursor carries it
   */
  public static String assertOpaque(
      String cursor, IdMasker masker, IdKind kind, Long expected, List<Long> internalIds) {
    List<String> parts = CursorToken.decode(cursor, 1);
    String key = parts.get(0);
    assertThat(key).matches(CANONICAL_UUID);
    for (Long id : internalIds) {
      assertThat(key).doesNotContain(id.toString());
      assertThat(cursor).doesNotContain(id.toString());
    }
    assertThat(masker.unmask(kind, UUID.fromString(key))).isEqualTo(expected);
    return key;
  }

  /**
   * Cursors that must all be refused next to a good one with this masked id: altered, in another
   * spelling, in the format of before TAR-227 (the internal key in clear), of a UUID that was never
   * issued, and of the same id masked as another kind.
   */
  public static List<String> forgeries(
      String key, IdMasker masker, IdKind otherKind, Long internalId, String sortKey) {
    char last = key.charAt(key.length() - 1);
    String altered = key.substring(0, key.length() - 1) + (last == '0' ? '1' : '0');
    String upper = key.toUpperCase(Locale.ROOT);
    // a UUID with no letter has no other case: then a spelling one digit too long
    String otherSpelling = upper.equals(key) ? key + "0" : upper;
    return List.of(
        CursorToken.encode(altered),
        CursorToken.encode(otherSpelling),
        CursorToken.encode(internalId.toString()),
        CursorToken.encode(sortKey, internalId.toString()),
        CursorToken.encode(UUID.randomUUID().toString()),
        CursorToken.encode(masker.mask(otherKind, internalId).toString()),
        key);
  }
}
