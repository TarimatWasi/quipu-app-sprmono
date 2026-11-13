package com.tarimatwasi.quipu.fine.application.port.out;

import com.tarimatwasi.quipu.fine.domain.Fine;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface FineRepositoryPort {

  /** What the fines of a month add up to, and how many they are. */
  record Total(BigDecimal amount, long count) {}

  /**
   * Stores a new fine. Needs a transaction.
   *
   * @throws FineGuestMissingException if the guest is not in the base
   */
  Fine insert(Long guestId, BigDecimal amount, YearMonth month, @Nullable String description);

  /** Finds the immutable fine, including its owner, before a deletion. */
  Optional<Fine> findById(Long id);

  /** Deletes the fine for good; false if there is none with that id. */
  boolean delete(Long id);

  /** The fines of the month, oldest first. */
  List<Fine> findByMonth(YearMonth month);

  /** The sum of the amounts of the fines of the month and their count; 0 if it has none. */
  Total totalOfMonth(YearMonth month);
}
