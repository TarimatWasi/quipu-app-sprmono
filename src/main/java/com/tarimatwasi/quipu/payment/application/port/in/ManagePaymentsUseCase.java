package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** RF-05, CU-05, CU-14: review the payments and their proofs, and register a payment by hand. */
public interface ManagePaymentsUseCase {

  /** RF-15: the largest voucher; the BFF checks it before it reads the bytes. */
  int MAX_VOUCHER_BYTES = 5 * 1024 * 1024;

  /** RN-22: a payment gathers at most five proofs. */
  int MAX_PROOFS = 5;

  /** Where a payment is in its review (RN-04). */
  enum PaymentState {
    PENDING,
    APPROVED,
    REJECTED
  }

  /** What an administrator can decide about a payment. */
  enum Decision {
    APPROVED,
    REJECTED
  }

  /** A guest with a contract or a guest of a one-time stay. */
  enum GuestKind {
    CONTRACT,
    TEMPORARY
  }

  /** What a proof is (RN-22). */
  enum ProofType {
    VOUCHER,
    CASH
  }

  /**
   * What became of what an approved payment left over (RN-38, RN-39): CREDIT is discounted from the
   * next period; REFUND_PENDING and REFUNDED belong to the last period of a contract or to a stay,
   * which Quipu returns.
   */
  enum SurplusOutcome {
    CREDIT,
    REFUND_PENDING,
    REFUNDED
  }

  /** What an approved payment verified over what it owed, and what became of it. */
  record SurplusView(BigDecimal amount, SurplusOutcome outcome) {}

  /**
   * Where a period or a stay of a direct payment stands (A5.1): UNPAID and OVERDUE take a direct
   * payment; UNDER_REVIEW and REJECTED have one, completed in the correction.
   */
  enum PayableState {
    UNPAID,
    OVERDUE,
    UNDER_REVIEW,
    REJECTED
  }

  /**
   * A period of a contract, or a stay, that the guest can pay or has pending (A5.1, TAR-240).
   *
   * @param dueDate the payment day of the period, or the exit date of the stay
   * @param creditApplied the credit of the guest discounted from it (RN-38)
   * @param amountDue {@code expectedAmount - creditApplied}
   * @param lastPeriod the last period of its contract, or a stay
   * @param paymentId set when UNDER_REVIEW or REJECTED
   */
  record PayablePeriodView(
      @Nullable Long contractId,
      @Nullable YearMonth period,
      @Nullable Long stayId,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PayableState status,
      LocalDate dueDate,
      BigDecimal expectedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean lastPeriod,
      @Nullable Long paymentId) {}

  /** Who added a proof (RN-22). */
  enum ProofSource {
    GUEST,
    ADMIN
  }

  /** The voucher of a proof, as the browser named it and as it arrived. */
  final class VoucherUpload {

    private final String originalName;
    private final byte[] content;

    /** The file with the name the browser gave it. */
    public VoucherUpload(String originalName, byte[] content) {
      this.originalName = originalName;
      this.content = content;
    }

    /** The name as the browser sent it; it is sanitized before it is stored. */
    public String originalName() {
      return originalName;
    }

    /** The bytes of the file; the caller must not change them. */
    public byte[] content() {
      return content;
    }
  }

  /**
   * A proof sent with a new payment.
   *
   * @param file the voucher; {@code null} for cash
   */
  record ProofUpload(
      ProofType kind, BigDecimal amount, @Nullable String method, @Nullable VoucherUpload file) {}

  /**
   * A proof of a correction (TAR-239): with {@code id}, a proof of the payment that is kept, its
   * amount or method changed and, with a file, its voucher replaced; without {@code id}, a new one.
   * A proof of the payment that is not listed is removed.
   *
   * @param file required for a new voucher, optional for a kept one; {@code null} for cash
   */
  record ProofEdit(
      @Nullable Long id,
      ProofType kind,
      BigDecimal amount,
      @Nullable String method,
      @Nullable VoucherUpload file) {}

  /** The file of a voucher, without its URL. */
  record ProofFileView(String name, String contentType, long sizeBytes) {}

  /**
   * A proof as a payment shows it.
   *
   * @param verifiedAmount {@code null} until the payment is approved
   * @param receivedOn when the administration received the money (direct payments only)
   * @param file {@code null} for cash
   * @param fileUrl signed for the administrator's detail only; {@code null} for cash and elsewhere
   */
  record ProofView(
      Long id,
      ProofType kind,
      @Nullable String method,
      BigDecimal declaredAmount,
      @Nullable BigDecimal verifiedAmount,
      ProofSource uploadedBy,
      Instant addedAt,
      @Nullable LocalDate receivedOn,
      @Nullable ProofFileView file,
      @Nullable String fileUrl) {}

  /** One decision on a payment (RN-05); the lists go oldest first. */
  record ReviewView(Decision decision, @Nullable String reason, Instant at) {}

  /** The administrator who resolved a payment (TAR-230); only in the administrator's views. */
  record ReviewerView(Long id, String name) {}

  /**
   * A row of the review queue: no amount, because the administrator compares the declared amounts
   * with the proofs before resolving (RN-16).
   *
   * @param environmentCode the environment of the contract, or of the stay (TAR-237); {@code null}
   *     for a stay without environment
   * @param period {@code null} for a temporary guest
   * @param stayStartDate {@code null} for a guest with a contract
   * @param stayEndDate {@code null} for a guest with a contract
   * @param reviewedBy {@code null} while pending
   * @param rejectionReason why it was rejected (TAR-296); {@code null} unless it is rejected
   * @param reviewedAt when it was resolved (TAR-296); {@code null} while pending
   * @param submittedAt the last time it was sent; it orders the queue
   */
  record QueueItemView(
      Long id,
      Long guestId,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PaymentState status,
      int proofCount,
      int voucherCount,
      boolean hasCash,
      @Nullable ReviewerView reviewedBy,
      @Nullable String rejectionReason,
      @Nullable Instant reviewedAt,
      Instant submittedAt,
      Instant createdAt) {}

  /**
   * A payment without its proofs' URLs.
   *
   * @param contractId {@code null} for the payment of a stay
   * @param stayId {@code null} for the payment of a period
   * @param period {@code null} for the payment of a stay
   * @param amount the sum of the declared amounts of its proofs
   * @param verifiedAmount the sum of the verified amounts; {@code null} until approved
   * @param paymentMethod the method of its first proof (deprecated in the contract)
   * @param submittedAt the last time it was sent
   */
  record PaymentView(
      Long id,
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      @Nullable BigDecimal verifiedAmount,
      @Nullable String paymentMethod,
      PaymentState status,
      @Nullable String rejectionReason,
      int proofCount,
      int voucherCount,
      boolean hasCash,
      @Nullable Instant reviewedAt,
      Instant submittedAt,
      Instant createdAt) {}

  /**
   * The administrator's detail of a payment (A5.2): what is paid, its proofs with their signed URLs
   * and its decisions.
   *
   * @param environmentCode {@code null} for a stay without environment
   * @param stayStartDate {@code null} for a guest with a contract
   * @param stayEndDate {@code null} for a guest with a contract
   * @param expectedAmount the monthly amount of the contract or the agreed amount of the stay
   * @param creditApplied the credit of the guest discounted from it (RN-38)
   * @param amountDue what the verified sum must cover to approve
   * @param lastPeriod the last period of the contract, or a stay: what is left over is refunded
   * @param reviewedBy {@code null} while pending
   * @param surplus what it verified over {@code amountDue} once approved; null otherwise
   */
  record PaymentDetailView(
      PaymentView payment,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal expectedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean lastPeriod,
      @Nullable ReviewerView reviewedBy,
      List<ProofView> proofs,
      List<ReviewView> reviews,
      @Nullable SurplusView surplus) {}

  /**
   * The administrator's decision; the reviewer is the one of the session.
   *
   * @param verified what was verified of each proof of the payment, by its id; required to approve
   */
  record Resolution(
      Long reviewerId,
      Decision decision,
      @Nullable String reason,
      Map<Long, BigDecimal> verified) {}

  /**
   * A payment the administrator registers on behalf of a guest: exactly one of {@code period} (a
   * guest with a contract) or {@code stayId} (a temporary guest).
   *
   * @param receivedOn when the money was received; {@code null} for the day of the registration
   */
  record DirectPayment(
      Long adminId,
      Long guestId,
      @Nullable YearMonth period,
      @Nullable Long stayId,
      @Nullable LocalDate receivedOn,
      List<ProofUpload> proofs) {}

  /**
   * One page of the queue, the payment sent longest ago first, read by last index; {@code status ==
   * null} lists every payment.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<QueueItemView> list(@Nullable PaymentState status, PageQuery page);

  /**
   * One page of the payment history of a guest of either type, newest first and read by last index
   * (RF-10), without the voucher URLs. The cursor is checked before the guest.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException no such guest
   */
  CursorPage<PaymentView> history(Long guestId, PageQuery page);

  /**
   * The state of every period that the contracts of a guest charge up to the current month (RF-06),
   * classified and ordered as the guest sees it in {@link OwnPaymentsUseCase#accountStatus}. A
   * guest without contracts, such as a temporary one, has none; an inactive guest still has its
   * periods.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException no such guest
   */
  List<OwnPaymentsUseCase.PeriodView> accountStatus(Long guestId);

  /**
   * The state of every period of the current contract of an environment (RF-06): the one ACTIVE
   * today (RN-07), classified and ordered as in {@link #accountStatus}. An environment without a
   * current contract (none, expired or terminated) has none; an inactive one still has its periods.
   *
   * @throws com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException no
   *     such one
   */
  List<OwnPaymentsUseCase.PeriodView> environmentAccountStatus(Long environmentId);

  /**
   * One payment with what it pays, its proofs with their signed URLs and its decisions.
   *
   * @throws PaymentNotFoundException if there is no payment with that id
   * @throws PaymentFileUnavailableException if a voucher cannot be signed
   */
  PaymentDetailView get(Long id);

  /**
   * What the guest can pay or has pending, for a direct payment (A5.1, CU-14, CU-28, RN-27, RN-38):
   * of a guest with a contract, every period its contracts charge up to the current month that is
   * not paid; of a temporary guest, every stay without an approved payment. Oldest first, for an
   * active or an inactive guest.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if there is no
   *     such guest
   */
  List<PayablePeriodView> payablePeriods(Long guestId);

  /**
   * Approves or rejects a pending payment, or a rejected one the administrator corrected (TAR-239).
   * To approve, every proof of the payment comes with what was verified, and their sum must cover
   * what is due (RN-16). Rejecting keeps the proofs: they are corrected and the payment is sent
   * again (RN-05). A reason is given with a rejection and is ignored with an approval.
   *
   * @throws PaymentNotFoundException if there is no payment with that id
   * @throws PaymentReasonRequiredException a rejection without a reason, or with a blank one
   * @throws InvalidPaymentException the reason is over 500 characters or has control characters, or
   *     ({@code proofs}) an approval lacks a proof, names another one or falls short
   * @throws PaymentAlreadyResolvedException the payment is already approved
   */
  PaymentView resolve(Long id, Resolution resolution);

  /**
   * Replaces the proofs of a pending or rejected payment (A5.2, TAR-239); its status does not
   * change. Every proof the administrator adds is recorded as added by the administration.
   *
   * @throws PaymentNotFoundException if there is no payment with that id
   * @throws InvalidPaymentException a proof is out of range, or names a proof of another payment
   * @throws InvalidVoucherFileException a file is not a JPG, PNG or PDF by content
   * @throws PaymentNotCorrectableException the payment is already approved
   * @throws PaymentFileUnavailableException the bucket failed; nothing was changed
   */
  PaymentView correct(Long adminId, Long id, List<ProofEdit> proofs);

  /**
   * Registers a payment already approved, each proof verified at its amount (RN-23). The checks go
   * in this order: the proofs and the choice of period or stay, the guest and the stay exist, the
   * guest is active, the period or the stay can be paid, the files are vouchers, the period or the
   * stay has no payment, and the proofs cover what is due.
   *
   * @throws InvalidPaymentException a field is out of range, both or neither of period and stay, or
   *     ({@code proofs}) none, more than five, or short of what is due
   * @throws InvalidVoucherFileException a file is not a JPG, PNG or PDF by content, or is over 5 MB
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException no such guest
   * @throws com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException the guest has no
   *     such stay
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws PaymentAlreadyExistsForPeriodException an approved payment already covers it
   * @throws VoucherAlreadyExistsException a payment is pending or rejected for it: correct it
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView registerDirect(DirectPayment payment);

  /**
   * TAR-237 (A3.2, A3.5): how a stay of a temporary guest stands, as the stays of the guest show
   * it.
   *
   * @param status PAID, UNDER_REVIEW, UNPAID or OVERDUE (OVERDUE once the exit date passed without
   *     an approved payment)
   * @param paymentId its payment, if it has one
   */
  record StayChargeView(String status, @Nullable Long paymentId) {}

  /** The payment status of the stay, which ends on that day (TAR-237). */
  StayChargeView stayCharge(Long stayId, LocalDate endDate);
}
