package com.tarimatwasi.quipu.shared.debt;

import java.time.YearMonth;
import java.util.List;

/**
 * What the contracts ask the payments about the debt of a guest (RN-25, RN-35). It lives in {@code
 * shared} because the payment module already depends on the contract module (modules may not form a
 * cycle).
 */
public interface GuestDebtQuery {

  /**
   * The months the guest owes, oldest first and without repeats, over every contract it ever had,
   * whatever its state; empty if it owes nothing. Needs no transaction of its own, it joins the one
   * of the caller.
   */
  List<YearMonth> owedPeriods(Long guestId);
}
