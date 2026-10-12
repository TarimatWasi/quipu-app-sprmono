package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.paging.PageQuery;
import org.junit.jupiter.api.Test;

/** BE-SPR-WEB-08, QP-SPRMONO-BFF-01 (TAR-176): what a page query must look like to be served. */
class PageParamsTest {

  @Test
  void query_withValidValues_buildsThePageQuery() {
    assertThat(PageParams.query(null, 20)).isEqualTo(new PageQuery(null, 20));
    assertThat(PageParams.query("abc", 100)).isEqualTo(new PageQuery("abc", 100));
  }

  @Test
  void query_withALimitOutOfRange_namesTheLimit() {
    assertThatThrownBy(() -> PageParams.query(null, 0))
        .isInstanceOfSatisfying(
            InvalidQueryParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo("limit"));
    assertThatThrownBy(() -> PageParams.query(null, 101))
        .isInstanceOfSatisfying(
            InvalidQueryParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo("limit"));
  }

  @Test
  void query_withABlankOrTooLongCursor_namesTheCursor() {
    assertThatThrownBy(() -> PageParams.query("", 20))
        .isInstanceOfSatisfying(
            InvalidQueryParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo("after"));
    assertThatThrownBy(() -> PageParams.query("x".repeat(513), 20))
        .isInstanceOfSatisfying(
            InvalidQueryParameterException.class,
            e -> assertThat(e.parameter()).isEqualTo("after"));
  }
}
