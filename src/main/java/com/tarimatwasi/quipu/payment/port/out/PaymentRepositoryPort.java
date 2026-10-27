package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface PaymentRepositoryPort {

  /** A payment as stored: the domain object and what only the storage knows. */
  record StoredPayment(
      Payment payment, String voucherKey, VoucherType voucherType, Instant createdAt) {}

  /** A payment the administrator registers by hand, which is approved at once (RN-23). */
  record DirectDraft(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      String voucherKey,
      String originalName,
      VoucherType voucherType,
      long sizeBytes,
      Instant reviewedAt,
      Long reviewedById) {}

  /** A voucher the guest uploads, which waits for the review (RN-04). */
  record VoucherDraft(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      String voucherKey,
      String originalName,
      VoucherType voucherType,
      long sizeBytes) {}

  /**
   * Stores the pending payment; the write is flushed, so a failure surfaces here.
   *
   * @throws LivePaymentExistsException if the period or the stay already has a live payment
   */
  StoredPayment insertPending(VoucherDraft draft);

  /**
   * Stores the approved payment; the write is flushed, so a failure surfaces here.
   *
   * @throws LivePaymentExistsException if the period or the stay already has a live payment
   */
  StoredPayment insertApproved(DirectDraft draft);

  Optional<StoredPayment> findById(Long id);

  /**
   * The guest of the payment when it is for a period of a contract; empty for the payment of a stay
   * or an unknown id. Reads a column, not the row, so a later {@link #findByIdForUpdate} in the
   * same transaction still sees the row as committed when its lock is granted (TAR-204).
   */
  Optional<Long> findContractGuestId(Long id);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so two requests
   * that resolve the same payment are decided one after the other. Needs a transaction.
   */
  Optional<StoredPayment> findByIdForUpdate(Long id);

  /** Stores the resolution of a payment that exists. */
  StoredPayment save(Payment resolved);

  /**
   * One page, oldest first; {@code status == null} reads every payment.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<StoredPayment> findQueue(@Nullable PaymentStatus status, PageQuery page);

  /**
   * One page of the payments of the guest, whatever their state, newest first (RF-10); empty if
   * there is no such guest.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<StoredPayment> findHistory(Long guestId, PageQuery page);

  /** The pending or approved payment of the period, if there is one. */
  Optional<Payment> findLiveByPeriod(Long contractId, YearMonth period);

  /** The pending or approved payment of the stay, if there is one. */
  Optional<Payment> findLiveByStay(Long stayId);

  /** Every payment of the contract, whatever its state. */
  List<Payment> findAllByContract(Long contractId);

  /** Every payment of the stay, whatever its state. */
  List<Payment> findAllByStay(Long stayId);

  /**
   * The approved payments of periods of the guest, of all its contracts in one read: the only ones
   * that clear a period of its debt (RN-25).
   */
  List<Payment> findApprovedOfContracts(Long guestId);

  /**
   * The sum of the approved payments of that period, of every contract (RF-18); 0 if there are
   * none. Pending and rejected payments do not count.
   */
  BigDecimal sumApprovedOfPeriod(YearMonth period);

  /**
   * The sum of the approved payments of those stays (RF-18); 0 if there are none. Pending and
   * rejected payments do not count.
   */
  BigDecimal sumApprovedOfStays(Collection<Long> stayIds);
}
