package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** RF-05, CU-05, CU-14: review the vouchers and register a payment by hand. */
public interface ManagePaymentsUseCase {

  /** RF-15: the largest voucher; the BFF checks it before it reads the bytes. */
  int MAX_VOUCHER_BYTES = 5 * 1024 * 1024;

  /** Where a voucher is in its review (RN-04). */
  enum PaymentState {
    PENDING,
    APPROVED,
    REJECTED
  }

  /** What an administrator can decide about a pending voucher. */
  enum Decision {
    APPROVED,
    REJECTED
  }

  /** A guest with a contract or a guest of a one-time stay. */
  enum GuestKind {
    CONTRACT,
    TEMPORARY
  }

  /**
   * A row of the review queue: no amount, because the administrator compares the declared one with
   * the voucher before resolving (RN-16).
   *
   * @param environmentCode {@code null} for a temporary guest
   * @param period {@code null} for a temporary guest
   */
  record QueueItemView(
      Long id,
      Long guestId,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable YearMonth period,
      PaymentState status,
      Instant createdAt) {}

  /**
   * A payment without its voucher URL.
   *
   * @param contractId {@code null} for the payment of a stay
   * @param stayId {@code null} for the payment of a period
   * @param period {@code null} for the payment of a stay
   */
  record PaymentView(
      Long id,
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      PaymentState status,
      @Nullable String rejectionReason,
      @Nullable Instant reviewedAt,
      Instant createdAt) {}

  /** A payment with the signed URL of its voucher (it expires and is not stored). */
  record PaymentDetailView(PaymentView payment, String voucherUrl) {}

  /** The administrator's decision; the reviewer is the one of the session. */
  record Resolution(Long reviewerId, Decision decision, @Nullable String reason) {}

  /** The voucher the administrator uploaded, as the browser named it and as it arrived. */
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
   * A payment the administrator registers on behalf of a guest: exactly one of {@code period} (a
   * guest with a contract) or {@code stayId} (a temporary guest).
   */
  record DirectPayment(
      Long adminId,
      Long guestId,
      @Nullable YearMonth period,
      @Nullable Long stayId,
      BigDecimal amount,
      @Nullable String paymentMethod,
      VoucherUpload voucher) {}

  /**
   * One page of the queue, oldest first and read by last index; {@code status == null} lists every
   * payment.
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
   * One payment with the signed URL of its voucher.
   *
   * @throws PaymentNotFoundException if there is no payment with that id
   * @throws PaymentFileUnavailableException if the voucher cannot be signed
   */
  PaymentDetailView get(Long id);

  /**
   * Approves or rejects a pending payment, once. Rejecting does not close the period: the guest can
   * upload another voucher (RN-05). A reason is given with a rejection and is ignored with an
   * approval.
   *
   * @throws PaymentNotFoundException if there is no payment with that id
   * @throws PaymentReasonRequiredException a rejection without a reason, or with a blank one
   * @throws InvalidPaymentException the reason is over 500 characters or has control characters
   * @throws PaymentAlreadyResolvedException the payment is no longer pending
   */
  PaymentView resolve(Long id, Resolution resolution);

  /**
   * Registers a payment already approved (RN-23). The checks go in this order: the amount and the
   * choice of period or stay, the guest and the stay exist, the guest is active, the period or the
   * stay can be paid, the file is a voucher, and the period or the stay has no live payment.
   *
   * @throws InvalidPaymentException a field is out of range, or both or neither of period and stay
   * @throws InvalidVoucherFileException the file is not a JPG, PNG or PDF by content, or is over 5
   *     MB
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException no such guest
   * @throws com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException the guest has no
   *     such stay
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws PaymentAlreadyExistsForPeriodException an approved payment already covers it
   * @throws VoucherAlreadyExistsException a voucher is still pending for it
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView registerDirect(DirectPayment payment);
}
