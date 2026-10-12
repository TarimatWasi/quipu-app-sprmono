package com.tarimatwasi.quipu.shared.paging;

import org.jspecify.annotations.Nullable;

/**
 * Which page of a list is asked for: the items after the cursor, at most {@code limit} of them
 * (BE-SPR-WEB-08). The cursor is opaque and comes from the previous page.
 */
public record PageQuery(@Nullable String after, int limit) {

  /** Items per page when the caller does not say. */
  public static final int DEFAULT_LIMIT = 20;

  /** The most items one page may carry. */
  public static final int MAX_LIMIT = 100;

  /** Refuses a limit out of range: the BFF has already answered 400 for it. */
  public PageQuery {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
  }

  /** The first page, with the default limit. */
  public static PageQuery first() {
    return new PageQuery(null, DEFAULT_LIMIT);
  }
}
