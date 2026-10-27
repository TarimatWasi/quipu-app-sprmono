package com.tarimatwasi.quipu.fine.port.in;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** RF-19: register, delete and list the fines of a month; only the administrator does it. */
public interface ManageFinesUseCase {

  /** A fine as the callers see it. */
  record FineView(
      Long id, Long guestId, BigDecimal amount, YearMonth month, @Nullable String description) {}

  /**
   * The data of a new fine. The amount is greater than 0 with at most two decimals. A blank
   * description means none.
   *
   * @param guestId {@code null} when the caller named a guest id that was never issued: it is
   *     answered as a guest that does not exist, once the data is valid
   */
  record FineCommand(
      @Nullable Long guestId, BigDecimal amount, YearMonth month, @Nullable String description) {}

  /**
   * Registers a fine as income of its month (RN-30), for any existing guest, active or inactive.
   *
   * @throws InvalidFineException if the amount is not valid
   * @throws InvalidFineDescriptionException if the description has more than 500 characters
   * @throws FineGuestNotFoundException if there is no guest with that id
   */
  FineView create(FineCommand command);

  /**
   * Deletes a fine for good (RN-24); there is no edit.
   *
   * @throws FineNotFoundException if there is no fine with that id
   */
  void delete(Long id);

  /** The fines of the month, oldest first and not paged: a month has few. */
  List<FineView> list(YearMonth month);
}
