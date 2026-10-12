package com.tarimatwasi.quipu.shared.paging;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list read by last index (keyset): no total, because counting walks the whole table.
 *
 * <p>A list read page by page while rows are written, or while the key a list is ordered by is
 * edited (a code, a document, a month), can skip or repeat a row: the cursor is a position in the
 * order, not a snapshot. That is acceptable for the ADMIN screens that use it.
 *
 * @param nextCursor what to ask for the next page; {@code null} on the last one
 */
public record CursorPage<T>(List<T> content, @Nullable String nextCursor) {

  /**
   * Builds the page from what a query returned when it was asked for one item more than the limit:
   * the extra one only says that there is a next page.
   */
  public static <T> CursorPage<T> of(
      List<T> fetched, int limit, Function<? super T, String> cursorOf) {
    if (fetched.size() <= limit) {
      return new CursorPage<>(List.copyOf(fetched), null);
    }
    List<T> content = List.copyOf(fetched.subList(0, limit));
    return new CursorPage<>(content, cursorOf.apply(content.get(limit - 1)));
  }

  /** The same page with every item converted. */
  public <R> CursorPage<R> map(Function<? super T, ? extends R> mapper) {
    return new CursorPage<>(content.stream().<R>map(mapper).toList(), nextCursor);
  }
}
