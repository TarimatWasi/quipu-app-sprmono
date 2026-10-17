package com.tarimatwasi.quipu.shared.debt;

import java.time.YearMonth;
import java.util.List;

/** The guest owes periods, so it cannot take a new contract until they are paid (RN-25, RN-35). */
public class GuestHasPendingDebtException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  // Never serialized: it only travels from the use case to the handler of the answer.
  private final transient List<YearMonth> periods;

  /**
   * @param periods the months owed, oldest first; never empty
   */
  public GuestHasPendingDebtException(List<YearMonth> periods) {
    super("The guest owes " + periods.size() + " period(s)");
    this.periods = List.copyOf(periods);
  }

  /** The months owed, oldest first. */
  public List<YearMonth> periods() {
    return periods;
  }
}
