package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofEdit;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ReviewView;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RF-04, RF-06, CU-16, CU-17, CU-21, CU-22: a guest sends its own payments with their proofs,
 * corrects a rejected one and reads the state of its own payments (RN-09). The guest is always the
 * one of the session; each operation belongs to one type of guest and refuses the other one.
 */
public interface OwnPaymentsUseCase {

  /** The state of a period or a stay, derived on the day it is asked (RN-06). */
  enum ChargeState {
    PAID,
    UNDER_REVIEW,
    UNPAID,
    OVERDUE
  }

  /**
   * One period of a contract of the guest.
   *
   * @param amount the monthly amount of the contract
   * @param creditApplied the credit discounted from it (RN-38)
   * @param amountDue what is left to pay: {@code amount - creditApplied}
   * @param paymentId the payment that holds the period, else the last rejection, if any
   * @param rejectionReason the reason of the last rejection while the payment stays rejected
   */
  record PeriodView(
      YearMonth period,
      ChargeState status,
      LocalDate dueDate,
      BigDecimal amount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      Long contractId,
      @Nullable Long paymentId,
      @Nullable String rejectionReason) {}

  /**
   * The stay of a temporary guest with the state of its payment.
   *
   * @param paymentId the payment that holds the stay, else the last rejection, if any
   * @param rejectionReason the reason of the last rejection while the payment stays rejected
   */
  record StayStatusView(
      Long stayId,
      LocalDate startDate,
      LocalDate endDate,
      ChargeState status,
      BigDecimal agreedAmount,
      @Nullable Long paymentId,
      @Nullable String rejectionReason) {}

  /**
   * One own payment as the guest sees it (H3 «Tu pago», H4): its proofs without URLs, its
   * decisions, and never who reviewed it (TAR-230).
   *
   * @param stayStartDate {@code null} for a period of a contract
   * @param creditApplied the credit discounted from it (RN-38)
   * @param amountDue what the verified sum must cover
   */
  record GuestPaymentView(
      Long id,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PaymentState status,
      BigDecimal amount,
      @Nullable BigDecimal verifiedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      @Nullable String rejectionReason,
      Instant submittedAt,
      Instant createdAt,
      List<ProofView> proofs,
      List<ReviewView> reviews) {}

  /** The payment of a month that a guest with a contract sends (H3). */
  record PeriodPayment(Long guestId, YearMonth period, List<ProofUpload> proofs) {}

  /** The payment of its stay that a temporary guest sends (T2). */
  record StayPayment(Long guestId, List<ProofUpload> proofs) {}

  /**
   * The state of every period that the contracts of the guest charge up to the current month, the
   * oldest contract first and each one oldest period first.
   *
   * @throws OtherGuestTypeException if the guest is temporary
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   */
  List<PeriodView> accountStatus(Long guestId);

  /**
   * The state of the payment of the stay in course, else the next one, else the last one.
   *
   * @throws OtherGuestTypeException if the guest has a contract
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws NoStayYetException if the guest has no stay
   */
  StayStatusView stayStatus(Long guestId);

  /**
   * One page of the own payment history of a guest with a contract, newest first and read by last
   * index (RF-10, CU-19, H4), each payment with its proofs and decisions. An inactive guest still
   * reads it.
   *
   * @throws OtherGuestTypeException if the guest is temporary
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws com.tarimatwasi.quipu.shared.paging.InvalidCursorException if the cursor is not one
   *     this service issued
   */
  CursorPage<GuestPaymentView> history(Long guestId, PageQuery page);

  /**
   * One own payment (H3, T2 «Tu pago»).
   *
   * @param contractType the type of guest the route belongs to
   * @throws OtherGuestTypeException if the guest is of the other type
   * @throws PaymentNotFoundException if no payment with that id belongs to the guest (RN-09)
   */
  GuestPaymentView payment(Long guestId, Long paymentId, boolean contractType);

  /**
   * Sends the payment of a period of the contract in force with its proofs, which waits for the
   * review. The checks go in this order: the type and the onboarding of the guest, the proofs, the
   * guest is active, it has a contract in force, the contract charges the period, the files are
   * vouchers, and the period has no payment.
   *
   * @throws OtherGuestTypeException if the guest is temporary
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws InvalidPaymentException a proof is out of range, or the contract does not charge it
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws NoActiveContractException the guest has no contract in force
   * @throws InvalidVoucherFileException a file is not a JPG, PNG or PDF by content, or is over 5 MB
   * @throws PaymentAlreadyExistsForPeriodException an approved payment already covers it
   * @throws VoucherAlreadyExistsException a payment is pending, or rejected (correct it), for it
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView uploadForPeriod(PeriodPayment payment);

  /**
   * Sends the one payment of a stay (RN-03) with its proofs, which waits for the review. The guest
   * does not choose the stay: it is the one in course, else the next one with no payment. The
   * checks go in the same order as for a period.
   *
   * @throws OtherGuestTypeException if the guest has a contract
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws InvalidPaymentException a proof is out of range
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws NoActiveStayException the guest has no stay in course nor to come
   * @throws InvalidVoucherFileException a file is not a JPG, PNG or PDF by content, or is over 5 MB
   * @throws PaymentAlreadyExistsForPeriodException the stay is already paid
   * @throws VoucherAlreadyExistsException a payment is pending, or rejected (correct it), for it
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView uploadForStay(StayPayment payment);

  /**
   * Corrects the proofs of an own rejected payment and sends it again, back to PENDING with a new
   * submission date (H3, T2 «Enviar de nuevo», RN-05).
   *
   * @param contractType the type of guest the route belongs to
   * @throws OtherGuestTypeException if the guest is of the other type
   * @throws PaymentNotFoundException if no payment with that id belongs to the guest (RN-09)
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws PaymentNotCorrectableException the payment is pending or approved
   * @throws InvalidPaymentException a proof is out of range, or names a proof of another payment
   * @throws InvalidVoucherFileException a file is not a JPG, PNG or PDF by content
   * @throws PaymentFileUnavailableException the bucket failed; nothing was changed
   */
  PaymentView correct(Long guestId, Long paymentId, boolean contractType, List<ProofEdit> proofs);
}
