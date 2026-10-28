package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import org.jspecify.annotations.Nullable;

/** The {@code limit} and {@code after} query parameters of the paged lists (BE-SPR-WEB-08). */
public final class PageParams {

  /** The contract limits the cursor to this many characters. */
  private static final int MAX_CURSOR_LENGTH = 512;

  private PageParams() {}

  /** The page query, or a 400 that names the parameter that is out of the contract. */
  public static PageQuery query(@Nullable String after, int limit) {
    if (limit < 1 || limit > PageQuery.MAX_LIMIT) {
      throw new InvalidQueryParameterException("limit");
    }
    if (after != null && (after.isEmpty() || after.length() > MAX_CURSOR_LENGTH)) {
      throw new InvalidQueryParameterException("after");
    }
    return new PageQuery(after, limit);
  }
}
