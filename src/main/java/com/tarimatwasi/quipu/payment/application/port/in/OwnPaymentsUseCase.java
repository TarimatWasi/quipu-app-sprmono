package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RF-04, RF-06, CU-16, CU-17, CU-21, CU-22: a guest uploads its own vouchers and reads the state of
 * its own payments (RN-09). The guest is always the one of the session; each operation belongs to
 * one type of guest and refuses the other one.
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
   * @param paymentId the live payment of the period, else the last rejected one, if any
   * @param rejectionReason the reason of the last rejection, only while no payment is alive
   */
  record PeriodView(
      YearMonth period,
      ChargeState status,
      LocalDate dueDate,
      BigDecimal amount,
      Long contractId,
      @Nullable Long paymentId,
      @Nullable String rejectionReason) {}

  /**
   * The stay of a temporary guest with the state of its payment.
   *
   * @param paymentId the live payment of the stay, else the last rejected one, if any
   * @param rejectionReason the reason of the last rejection, only while no payment is alive
   */
  record StayStatusView(
      Long stayId,
      LocalDate startDate,
      LocalDate endDate,
      ChargeState status,
      BigDecimal agreedAmount,
      @Nullable Long paymentId,
      @Nullable String rejectionReason) {}

  /** The voucher of a month that a guest with a contract uploads. */
  record PeriodVoucher(
      Long guestId,
      YearMonth period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      VoucherUpload voucher) {}

  /** The voucher of its stay that a temporary guest uploads. */
  record StayVoucher(
      Long guestId, BigDecimal amount, @Nullable String paymentMethod, VoucherUpload voucher) {}

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
   * index (RF-10, CU-19), without the voucher URLs; a rejected payment carries its reason. An
   * inactive guest still reads it.
   *
   * @throws OtherGuestTypeException if the guest is temporary
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws com.tarimatwasi.quipu.shared.paging.InvalidCursorException if the cursor is not one
   *     this service issued
   */
  CursorPage<PaymentView> history(Long guestId, PageQuery page);

  /**
   * Uploads the voucher of a period of the contract in force, which waits for the review. The
   * checks go in this order: the type and the onboarding of the guest, the amount and the method,
   * the guest is active, it has a contract in force, the contract charges the period, the file is a
   * voucher, and the period has no live payment.
   *
   * @throws OtherGuestTypeException if the guest is temporary
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws InvalidPaymentException a field is out of range, or the contract does not charge it
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws NoActiveContractException the guest has no contract in force
   * @throws InvalidVoucherFileException the file is not a JPG, PNG or PDF by content, or is over 5
   *     MB
   * @throws PaymentAlreadyExistsForPeriodException an approved payment already covers it
   * @throws VoucherAlreadyExistsException a voucher is still pending for it
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView uploadForPeriod(PeriodVoucher voucher);

  /**
   * Uploads the one voucher of a stay (RN-03), which waits for the review. The guest does not
   * choose the stay: it is the one in course, else the next one with no live payment. The checks go
   * in the same order as for a period.
   *
   * @throws OtherGuestTypeException if the guest has a contract
   * @throws OnboardingRequiredException if the guest has not finished the onboarding
   * @throws InvalidPaymentException a field is out of range
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException the guest is
   *     not active
   * @throws NoActiveStayException the guest has no stay in course nor to come
   * @throws InvalidVoucherFileException the file is not a JPG, PNG or PDF by content, or is over 5
   *     MB
   * @throws PaymentAlreadyExistsForPeriodException the stay is already paid
   * @throws VoucherAlreadyExistsException a voucher is still pending for the stay
   * @throws PaymentFileUnavailableException the bucket failed; nothing was registered
   */
  PaymentView uploadForStay(StayVoucher voucher);
}
