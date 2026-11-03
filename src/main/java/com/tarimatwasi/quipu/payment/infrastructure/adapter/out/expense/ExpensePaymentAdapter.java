package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.expense;

import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentExpensePort;
import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/** The payment module asks the expense module, through its exposed port, about a month. */
@Component
public class ExpensePaymentAdapter implements PaymentExpensePort {

  private final ManageExpensesUseCase expenses;

  ExpensePaymentAdapter(ManageExpensesUseCase expenses) {
    this.expenses = expenses;
  }

  @Override
  public MonthTotal totalOf(YearMonth month) {
    var total = expenses.totalOf(month);
    return new MonthTotal(total.amount(), total.count());
  }
}
