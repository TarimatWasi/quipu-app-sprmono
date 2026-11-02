package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import com.tarimatwasi.quipu.payment.domain.Refund;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The refunds Quipu made to the guests (RF-24, RN-39, TAR-240). */
public interface RefundRepositoryPort {

  /** Stores a new refund; who registered it is the user of the session. */
  Refund insert(
      Long guestId,
      Long fromPaymentId,
      BigDecimal amount,
      LocalDate date,
      String method,
      @Nullable String note);

  /** Every refund of the guest, in no given order. */
  List<Refund> findByGuest(Long guestId);

  /**
   * One page of the refunds dated in the month, newest first.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<Refund> findByMonth(YearMonth month, PageQuery page);

  /** What the refunds dated in the month add up to and how many they are (RF-18). */
  MonthTotal totalOfMonth(YearMonth month);
}
