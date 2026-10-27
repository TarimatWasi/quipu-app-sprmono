package com.tarimatwasi.quipu.payment.adapter.out.expense;

import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.payment.port.out.PaymentExpensePort;
import java.math.BigDecimal;
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
  public BigDecimal totalOf(YearMonth month) {
    return expenses.totalOf(month);
  }
}
