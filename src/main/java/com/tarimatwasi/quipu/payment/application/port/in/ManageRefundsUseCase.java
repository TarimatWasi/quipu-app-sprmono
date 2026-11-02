package com.tarimatwasi.quipu.payment.application.port.in;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * RF-24, RN-38, RN-39 (TAR-240): the administrator registers what Quipu returns to a guest from its
 * pending credit, lists the refunds of a month and the guests with credit to refund.
 */
public interface ManageRefundsUseCase {

  /**
   * A new refund. A blank note means none.
   *
   * @param guestId {@code null} when the caller named a guest id that was never issued: it is
   *     answered as a guest that does not exist, once the data is valid
   */
  record RefundCommand(
      @Nullable Long guestId,
      BigDecimal amount,
      LocalDate date,
      String method,
      @Nullable String note) {}

  /**
   * A refund with the guest it went to.
   *
   * @param environmentCode the environment of the contract or stay the credit came from; null for a
   *     stay without environment
   */
  record RefundView(
      Long id,
      Long guestId,
      @Nullable String guestName,
      @Nullable String environmentCode,
      BigDecimal amount,
      LocalDate date,
      String method,
      @Nullable String note,
      Instant createdAt) {}

  /** One page of the refunds of a month and how many the month has. */
  record RefundPage(CursorPage<RefundView> page, long totalCount) {}

  /**
   * The credit a guest has to get back (RN-38), after any partial refund.
   *
   * @param paymentId the payment it came from: the oldest one not refunded whole
   * @param contractEndedOn the end or termination date of the contract it came from; null for a
   *     stay
   */
  record PendingCreditView(
      Long guestId,
      @Nullable String guestName,
      GuestKind guestType,
      @Nullable String environmentCode,
      BigDecimal amount,
      Long paymentId,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable LocalDate contractEndedOn) {}

  /**
   * Registers a refund; who registered it is the user of the session. Two refunds of one guest are
   * registered one after the other, so together they never exceed its pending credit.
   *
   * @throws InvalidRefundException with its field: {@code amount} not greater than 0, with more
   *     than two decimals, too big or more than the pending credit; {@code date} in the future;
   *     {@code method} blank or over 50; {@code note} over 500
   * @throws RefundGuestNotFoundException if there is no guest with that id
   */
  RefundView create(RefundCommand command);

  /**
   * One page of the refunds dated in the month, newest first.
   *
   * @throws InvalidCursorException if the cursor is not one this service issued
   * @throws InvalidFinanceMonthException if the year of the month is not from 1 to 9999
   */
  RefundPage list(YearMonth month, PageQuery page);

  /** The guests with credit to refund, one each, the oldest credit first. */
  List<PendingCreditView> pendingCredits();
}
