package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The 409 of a guest that owes periods; each one as {@code yyyy-MM}, oldest first (RN-25), and
 * everything owed with its amount (TAR-237).
 */
public record BffGuestHasPendingDebtResponse(
    String code, String message, List<String> periods, List<DebtResponse> debts) {

  /** A period or an unpaid stay; the fields that do not apply are null, never left out. */
  public record DebtResponse(
      @Nullable String period,
      @Nullable UUID stayId,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal amountDue) {}
}
