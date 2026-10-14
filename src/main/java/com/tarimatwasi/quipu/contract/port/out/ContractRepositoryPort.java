package com.tarimatwasi.quipu.contract.port.out;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface ContractRepositoryPort {

  /** A contract that is not stored yet. */
  record Draft(
      Long environmentId,
      Long guestId,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      String fileKey,
      String originalName,
      long sizeBytes) {}

  /** Stores a new ACTIVE contract; the write is flushed, so a failure surfaces here. */
  Contract insert(Draft draft);

  Optional<Contract> findById(Long id);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so two requests
   * that finish the same contract are decided one after the other. Needs a transaction.
   */
  Optional<Contract> findByIdForUpdate(Long id);

  /** The most recently loaded contract of the guest, whatever its state. */
  Optional<Contract> findLatestByGuest(Long guestId);

  /**
   * One page, newest first; {@code status == null} reads every contract. The status is the one the
   * contract has on {@code today}.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<Contract> findAll(@Nullable ContractStatus status, LocalDate today, PageQuery page);

  /** Whether an ACTIVE contract (not terminated, not past its end) holds the environment. */
  boolean existsInForce(Long environmentId, LocalDate today);

  /** Stores the terminated state of an existing contract. */
  Contract save(Contract contract);
}
