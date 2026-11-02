package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.in.FinanceSummaryUseCase.FinanceSummaryView;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidFinanceMonthException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentExpensePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFinePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FinanceSummaryServiceTest {

  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentStayPort stays;
  @Mock PaymentFinePort fines;
  @Mock PaymentExpensePort expenses;
  @Mock RefundRepositoryPort refunds;

  private FinanceSummaryService service() {
    return new FinanceSummaryService(payments, stays, fines, expenses, refunds);
  }

  private static MonthTotal total(String amount, long count) {
    return new MonthTotal(new BigDecimal(amount), count);
  }

  @Test
  void addsThePeriodsOfTheMonthTheStaysThatStartInItTheFinesLessTheRefundsAndTheExpenses() {
    when(payments.approvedOfPeriod(OCTOBER)).thenReturn(total("950.10", 2));
    when(stays.idsStartingIn(OCTOBER)).thenReturn(List.of(7L, 9L));
    when(payments.approvedOfStays(List.of(7L, 9L))).thenReturn(total("150.20", 1));
    when(fines.totalOf(OCTOBER)).thenReturn(total("25.30", 1));
    when(refunds.totalOfMonth(OCTOBER)).thenReturn(total("100.00", 1));
    when(expenses.totalOf(OCTOBER)).thenReturn(total("2000.00", 4));

    assertThat(service().summary(OCTOBER))
        .isEqualTo(
            new FinanceSummaryView(
                OCTOBER,
                new BigDecimal("1100.30"),
                3,
                new BigDecimal("25.30"),
                1,
                new BigDecimal("100.00"),
                1,
                new BigDecimal("1025.60"),
                new BigDecimal("2000.00"),
                4,
                new BigDecimal("-974.40")));
  }

  @Test
  void aMonthWithoutStaysDoesNotAskForTheirPayments() {
    when(payments.approvedOfPeriod(OCTOBER)).thenReturn(MonthTotal.NONE);
    when(stays.idsStartingIn(OCTOBER)).thenReturn(List.of());
    when(fines.totalOf(OCTOBER)).thenReturn(MonthTotal.NONE);
    when(refunds.totalOfMonth(OCTOBER)).thenReturn(MonthTotal.NONE);
    when(expenses.totalOf(OCTOBER)).thenReturn(MonthTotal.NONE);

    FinanceSummaryView summary = service().summary(OCTOBER);

    assertThat(summary.totalIncome()).hasToString("0.00");
    assertThat(summary.net()).hasToString("0.00");
    verify(payments, never()).approvedOfStays(any());
  }

  @Test
  void aMonthOfYearZeroIsRefusedBeforeAnythingIsRead() {
    assertThatThrownBy(() -> service().summary(YearMonth.of(0, 1)))
        .isInstanceOf(InvalidFinanceMonthException.class);
    verifyNoInteractions(payments, stays, fines, expenses, refunds);
  }
}
