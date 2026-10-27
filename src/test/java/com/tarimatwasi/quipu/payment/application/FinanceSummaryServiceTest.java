package com.tarimatwasi.quipu.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.port.in.FinanceSummaryUseCase.FinanceSummaryView;
import com.tarimatwasi.quipu.payment.port.in.InvalidFinanceMonthException;
import com.tarimatwasi.quipu.payment.port.out.PaymentExpensePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFinePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
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

  private FinanceSummaryService service() {
    return new FinanceSummaryService(payments, stays, fines, expenses);
  }

  @Test
  void addsThePeriodsOfTheMonthTheStaysThatStartInItTheFinesAndTheExpenses() {
    when(payments.sumApprovedOfPeriod(OCTOBER)).thenReturn(new BigDecimal("950.10"));
    when(stays.idsStartingIn(OCTOBER)).thenReturn(List.of(7L, 9L));
    when(payments.sumApprovedOfStays(List.of(7L, 9L))).thenReturn(new BigDecimal("150.20"));
    when(fines.totalOf(OCTOBER)).thenReturn(new BigDecimal("25.30"));
    when(expenses.totalOf(OCTOBER)).thenReturn(new BigDecimal("2000.00"));

    assertThat(service().summary(OCTOBER))
        .isEqualTo(
            new FinanceSummaryView(
                OCTOBER,
                new BigDecimal("1100.30"),
                new BigDecimal("25.30"),
                new BigDecimal("1125.60"),
                new BigDecimal("2000.00"),
                new BigDecimal("-874.40")));
  }

  @Test
  void aMonthWithoutStaysDoesNotAskForTheirPayments() {
    when(payments.sumApprovedOfPeriod(OCTOBER)).thenReturn(BigDecimal.ZERO);
    when(stays.idsStartingIn(OCTOBER)).thenReturn(List.of());
    when(fines.totalOf(OCTOBER)).thenReturn(BigDecimal.ZERO);
    when(expenses.totalOf(OCTOBER)).thenReturn(BigDecimal.ZERO);

    FinanceSummaryView summary = service().summary(OCTOBER);

    assertThat(summary.totalIncome()).hasToString("0.00");
    assertThat(summary.net()).hasToString("0.00");
    verify(payments, never()).sumApprovedOfStays(any());
  }

  @Test
  void aMonthOfYearZeroIsRefusedBeforeAnythingIsRead() {
    assertThatThrownBy(() -> service().summary(YearMonth.of(0, 1)))
        .isInstanceOf(InvalidFinanceMonthException.class);
    verifyNoInteractions(payments, stays, fines, expenses);
  }
}
