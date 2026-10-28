package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A page of a list as the BFF contract says: the items and the cursor of the next page, which is
 * {@code null} (and still present) on the last one.
 */
public record PagedResponse<T>(List<T> content, @Nullable String nextCursor) {

  /** The page of the use case with every item converted to its response. */
  public static <S, T> PagedResponse<T> of(
      CursorPage<S> page, Function<? super S, ? extends T> mapper) {
    CursorPage<T> mapped = page.map(mapper);
    return new PagedResponse<>(mapped.content(), mapped.nextCursor());
  }
}
