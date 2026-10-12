package com.tarimatwasi.quipu.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** BE-SPR-WEB-08 (TAR-176): the cursor is opaque and a bad one is refused, never trusted. */
class CursorTokenTest {

  @Test
  void decode_afterEncode_returnsTheSameParts() {
    String token = CursorToken.encode("A-101", "a.b|c", "ñandú", "");

    assertThat(CursorToken.decode(token, 4)).isEqualTo(List.of("A-101", "a.b|c", "ñandú", ""));
  }

  @Test
  void encode_producesAUrlSafeToken() {
    assertThat(CursorToken.encode("¿qué?", "x/y+z")).matches("[A-Za-z0-9_.-]+");
  }

  @Test
  void decode_withAnotherNumberOfParts_isRefused() {
    String token = CursorToken.encode("one", "two");

    assertThatThrownBy(() -> CursorToken.decode(token, 3))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void decode_ofGarbage_isRefused() {
    assertThatThrownBy(() -> CursorToken.decode("not a cursor!", 1))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> CursorToken.decode("", 1)).isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> CursorToken.decode("%%%.@@@", 2))
        .isInstanceOf(InvalidCursorException.class);
  }
}
