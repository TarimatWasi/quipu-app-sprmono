package com.tarimatwasi.quipu.fine.port.out;

import com.tarimatwasi.quipu.fine.domain.Fine;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface FineRepositoryPort {

  /**
   * Stores a new fine. Needs a transaction.
   *
   * @throws FineGuestMissingException if the guest is not in the base
   */
  Fine insert(Long guestId, BigDecimal amount, YearMonth month, @Nullable String description);

  /** Deletes the fine for good; false if there is none with that id. */
  boolean delete(Long id);

  /** The fines of the month, oldest first. */
  List<Fine> findByMonth(YearMonth month);
}
