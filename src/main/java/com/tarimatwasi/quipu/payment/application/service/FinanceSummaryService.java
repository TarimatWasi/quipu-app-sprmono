package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.FinanceSummaryUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidFinanceMonthException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentExpensePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFinePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import com.tarimatwasi.quipu.payment.domain.MonthlyBalance;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RF-18: the balance of a month. Every sum is added up by the base; the other modules answer
 * through their ports, inside the same read-only transaction.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class FinanceSummaryService implements FinanceSummaryUseCase {

  private final PaymentRepositoryPort payments;
  private final PaymentStayPort stays;
  private final PaymentFinePort fines;
  private final PaymentExpensePort expenses;
  private final RefundRepositoryPort refunds;

  FinanceSummaryService(
      PaymentRepositoryPort payments,
      PaymentStayPort stays,
      PaymentFinePort fines,
      PaymentExpensePort expenses,
      RefundRepositoryPort refunds) {
    this.refunds = refunds;
    this.payments = payments;
    this.stays = stays;
    this.fines = fines;
    this.expenses = expenses;
  }

  @Override
  @Transactional(readOnly = true)
  public FinanceSummaryView summary(YearMonth month) {
    if (!MonthlyBalance.isValidMonth(month)) {
      throw new InvalidFinanceMonthException();
    }
    // D-68: a contract payment counts in its period, a stay payment in the month its stay starts.
    MonthTotal ofPeriods = payments.approvedOfPeriod(month);
    List<Long> staysOfTheMonth = stays.idsStartingIn(month);
    MonthTotal ofStays =
        staysOfTheMonth.isEmpty() ? MonthTotal.NONE : payments.approvedOfStays(staysOfTheMonth);
    MonthlyBalance balance =
        MonthlyBalance.of(
            month,
            ofPeriods.plus(ofStays),
            fines.totalOf(month),
            refunds.totalOfMonth(month),
            expenses.totalOf(month));
    return new FinanceSummaryView(
        balance.month(),
        balance.incomeFromPayments(),
        balance.paymentsCount(),
        balance.incomeFromFines(),
        balance.finesCount(),
        balance.refundsTotal(),
        balance.refundsCount(),
        balance.totalIncome(),
        balance.totalExpenses(),
        balance.expensesCount(),
        balance.net());
  }
}
