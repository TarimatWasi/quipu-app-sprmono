package com.tarimatwasi.quipu.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageQueryTest {

  @Test
  void first_usesTheDefaultLimitAndNoCursor() {
    PageQuery query = PageQuery.first();

    assertThat(query.after()).isNull();
    assertThat(query.limit()).isEqualTo(PageQuery.DEFAULT_LIMIT).isEqualTo(20);
  }

  @Test
  void constructor_acceptsTheLimitsOfTheContract() {
    assertThat(new PageQuery(null, 1).limit()).isEqualTo(1);
    assertThat(new PageQuery("x", PageQuery.MAX_LIMIT).limit()).isEqualTo(100);
  }

  @Test
  void constructor_refusesALimitOutOfRange() {
    assertThatThrownBy(() -> new PageQuery(null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageQuery(null, 101)).isInstanceOf(IllegalArgumentException.class);
  }
}
