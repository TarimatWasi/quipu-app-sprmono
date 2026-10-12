package com.tarimatwasi.quipu.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CursorPageTest {

  @Test
  void of_withOneMoreThanTheLimit_keepsTheLimitAndPointsAtTheLastKept() {
    CursorPage<String> page = CursorPage.of(List.of("a", "b", "c"), 2, item -> "after-" + item);

    assertThat(page.content()).containsExactly("a", "b");
    assertThat(page.nextCursor()).isEqualTo("after-b");
  }

  @Test
  void of_withNoMoreThanTheLimit_hasNoNextCursor() {
    CursorPage<String> page = CursorPage.of(List.of("a", "b"), 2, item -> "after-" + item);

    assertThat(page.content()).containsExactly("a", "b");
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void map_keepsTheCursorAndConvertsTheContent() {
    CursorPage<Integer> page = new CursorPage<>(List.of("a", "bb"), "next").map(String::length);

    assertThat(page.content()).containsExactly(1, 2);
    assertThat(page.nextCursor()).isEqualTo("next");
  }
}
