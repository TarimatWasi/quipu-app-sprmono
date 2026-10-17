package com.tarimatwasi.quipu.contract.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What the payments need to know of the contracts: their terms, never the file. */
public interface ContractTermsUseCase {

  /**
   * The terms of a contract.
   *
   * @param endDate {@code null} for an open-ended contract
   * @param terminatedAt when it was finished by hand, if it was
   */
  record ContractTermsView(
      Long contractId,
      Long environmentId,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable Instant terminatedAt,
      int paymentDay,
      int graceDays,
      BigDecimal monthlyAmount) {}

  /** Every contract of the guest, whatever its state, the oldest first. */
  List<ContractTermsView> termsOfGuest(Long guestId);

  /**
   * Like {@link #termsOfGuest} but read with the rows locked until the transaction of the caller
   * ends, so a termination that races the read is seen or waits. The oldest id first. Needs a
   * transaction.
   */
  List<ContractTermsView> lockedTermsOfGuest(Long guestId);

  /** The environment of each contract; an id with no contract adds nothing. */
  Map<Long, Long> environmentsOf(Collection<Long> contractIds);
}
