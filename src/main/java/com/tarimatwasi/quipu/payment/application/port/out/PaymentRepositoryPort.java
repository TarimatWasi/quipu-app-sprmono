package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.PaymentReview;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.ProofKind;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface PaymentRepositoryPort {

  /**
   * A payment as stored: the domain object with its proofs, in the order they were added, and what
   * only the storage knows.
   */
  record StoredPayment(Payment payment, List<PaymentProof> proofs, Instant createdAt) {}

  /**
   * A proof to add (RN-22).
   *
   * @param verifiedAmount set only for a proof of a payment approved at once (RN-23)
   * @param file {@code null} for cash
   */
  record NewProof(
      ProofKind kind,
      BigDecimal declaredAmount,
      @Nullable BigDecimal verifiedAmount,
      @Nullable String method,
      @Nullable ProofFile file,
      ProofAuthor uploadedBy,
      @Nullable LocalDate receivedOn) {}

  /**
   * A payment to store with its proofs: PENDING when the guest sends it (RN-04), APPROVED when the
   * administrator registers it by hand (RN-23), which also records its decision.
   *
   * @param reviewedAt set only for an approved one
   * @param reviewedById set only for an approved one
   */
  record NewPayment(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      PaymentStatus status,
      ProofAuthor recordedBy,
      List<NewProof> proofs,
      Instant submittedAt,
      @Nullable Instant reviewedAt,
      @Nullable Long reviewedById) {}

  /**
   * Stores the payment with its proofs (and its decision when approved); the write is flushed, so a
   * failure surfaces here.
   *
   * @throws LivePaymentExistsException if the period or the stay already has a payment that holds
   *     it
   */
  StoredPayment insert(NewPayment payment);

  Optional<StoredPayment> findById(Long id);

  /**
   * The guest of the payment when it is for a period of a contract; empty for the payment of a stay
   * or an unknown id. Reads a column, not the row, so a later {@link #findByIdForUpdate} in the
   * same transaction still sees the row as committed when its lock is granted (TAR-204).
   */
  Optional<Long> findContractGuestId(Long id);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so two requests
   * that resolve or correct the same payment are decided one after the other. Needs a transaction.
   */
  Optional<StoredPayment> findByIdForUpdate(Long id);

  /** Stores the new state of a payment that exists (its proofs are written apart). */
  StoredPayment save(Payment changed);

  /** Records one decision on a payment (RN-05, TAR-230). */
  void addReview(Long paymentId, PaymentReview review);

  /** Every decision on the payment, oldest first. */
  List<PaymentReview> findReviews(Long paymentId);

  /** Every decision on each of those payments, oldest first, in one read per slice of ids. */
  Map<Long, List<PaymentReview>> findReviewsOf(Collection<Long> paymentIds);

  /** Adds a proof to a payment that exists. */
  void addProof(Long paymentId, NewProof proof);

  /** Stores the new amounts, method or file of a proof that exists. */
  void updateProof(PaymentProof proof);

  /** Removes those proofs of the payment. */
  void removeProofs(Long paymentId, Collection<Long> proofIds);

  /**
   * One page, the payment sent longest ago first (by {@code submittedAt}, TAR-239); {@code status
   * == null} reads every payment.
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

  /** The pending, approved or rejected payment that holds the period, if there is one. */
  Optional<Payment> findLiveByPeriod(Long contractId, YearMonth period);

  /** The pending, approved or rejected payment that holds the stay, if there is one. */
  Optional<Payment> findLiveByStay(Long stayId);

  /** Every payment of the contract, whatever its state. */
  List<Payment> findAllByContract(Long contractId);

  /**
   * Every payment, whatever its state, of any of those contracts, in as few reads as the list
   * allows (TAR-236); none for no contracts.
   */
  List<Payment> findAllByContracts(Collection<Long> contractIds);

  /** Every payment in exactly that state (TAR-236: the rejected ones nobody corrected yet). */
  List<Payment> findByStatus(PaymentStatus status);

  /** Every payment of the stay, whatever its state. */
  List<Payment> findAllByStay(Long stayId);

  /** Every payment of the guest, whatever its state and of any contract or stay (RN-38). */
  List<Payment> findAllOfGuest(Long guestId);

  /** Like {@link #findAllOfGuest(Long)} for several guests in one read (BE-SPR-DAT-10). */
  List<Payment> findAllOfGuests(Collection<Long> guestIds);

  /**
   * The guests with an approved payment: the only ones that can have credit to refund (RN-38),
   * since a credit always starts with a payment verified over what it owed.
   */
  List<Long> guestsWithApprovedPayments();

  /**
   * When the payment of a history cursor was created, to place the refunds of the guest between the
   * pages of its history (H4, TAR-240).
   *
   * @throws InvalidCursorException if the cursor is not one this service issued for the guest
   */
  Instant historyPosition(Long guestId, String cursor);

  /**
   * Every payment, whatever its state, of that period of any of those contracts, in as few reads as
   * the list allows (HU-A04); none for no contracts.
   */
  List<Payment> findAllByContractsAndPeriod(Collection<Long> contractIds, YearMonth period);

  /**
   * Every payment, whatever its state, of any of those stays, in as few reads as the list allows
   * (HU-A04); none for no stays.
   */
  List<Payment> findAllByStays(Collection<Long> stayIds);

  /** How many payments are in that state. */
  long countByStatus(PaymentStatus status);

  /**
   * The approved payments of periods of the guest, of all its contracts in one read: the only ones
   * that clear a period of its debt (RN-25).
   */
  List<Payment> findApprovedOfContracts(Long guestId);

  /**
   * The sum of what was verified of the approved payments of that period, of every contract, and
   * how many they are (RF-18); 0 if there are none. Pending and rejected payments do not count.
   */
  MonthTotal approvedOfPeriod(YearMonth period);

  /**
   * The sum of what was verified of the approved payments of those stays and how many they are
   * (RF-18); 0 if there are none. Pending and rejected payments do not count.
   */
  MonthTotal approvedOfStays(Collection<Long> stayIds);
}
