package com.tarimatwasi.quipu.shared.debt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * One thing a guest owes, with what is left to pay of it (TAR-237): a period of its contracts, or
 * an unpaid stay of a temporary guest.
 *
 * @param period set for a period of a contract
 * @param stayId set for a stay, with its dates
 * @param amountDue what is left to pay, with the credit of the contract read in (RN-38)
 */
public record Debt(
    @Nullable YearMonth period,
    @Nullable Long stayId,
    @Nullable LocalDate stayStartDate,
    @Nullable LocalDate stayEndDate,
    BigDecimal amountDue) {

  /** A period of the contracts of the guest. */
  public static Debt ofPeriod(YearMonth period, BigDecimal amountDue) {
    return new Debt(period, null, null, null, amountDue);
  }

  /** An unpaid stay of a temporary guest. */
  public static Debt ofStay(Long stayId, LocalDate start, LocalDate end, BigDecimal amountDue) {
    return new Debt(null, stayId, start, end, amountDue);
  }
}
