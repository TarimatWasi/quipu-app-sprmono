package com.tarimatwasi.quipu.shared.debt;

import java.io.Serial;
import java.time.YearMonth;
import java.util.List;

/** The guest owes periods, so it cannot take a new contract until they are paid (RN-25, RN-35). */
public class GuestHasPendingDebtException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  // Never serialized: they only travel from the use case to the handler of the answer.
  private final transient List<YearMonth> periods;
  private final transient List<Debt> debts;

  /**
   * @param periods the months owed, oldest first; never empty
   * @param debts everything owed with its amount, oldest first (TAR-237)
   */
  public GuestHasPendingDebtException(List<YearMonth> periods, List<Debt> debts) {
    super("The guest owes " + periods.size() + " period(s)");
    this.periods = List.copyOf(periods);
    this.debts = List.copyOf(debts);
  }

  /** The months owed, oldest first. */
  public List<YearMonth> periods() {
    return periods;
  }

  /** Everything owed with its amount, oldest first. */
  public List<Debt> debts() {
    return debts;
  }
}
