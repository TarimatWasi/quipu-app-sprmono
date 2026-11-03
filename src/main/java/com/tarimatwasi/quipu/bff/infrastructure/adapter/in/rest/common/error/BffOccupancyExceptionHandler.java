package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffEnvironmentOccupiedResponse.OccupantResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.BffGuestHasPendingDebtResponse.DebtResponse;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNamesUseCase;
import com.tarimatwasi.quipu.shared.debt.Debt;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import java.time.YearMonth;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RN-12, RN-20, RN-25, RN-35 (TAR-237): the 409 that say who holds an environment and what a guest
 * owes, apart from {@link BffExceptionHandler} so that it stays within the size of a file
 * (BE-SPR-COD-09).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffOccupancyExceptionHandler {

  private final GuestNamesUseCase guests;
  private final IdMasker ids;

  public BffOccupancyExceptionHandler(GuestNamesUseCase guests, IdMasker ids) {
    this.guests = guests;
    this.ids = ids;
  }

  /**
   * RN-25, RN-35: a guest that owes periods takes no new contract and is not reactivated; the
   * months, oldest first, and everything owed with its amount (TAR-237).
   */
  @ExceptionHandler(GuestHasPendingDebtException.class)
  public ResponseEntity<BffGuestHasPendingDebtResponse> handleGuestHasPendingDebt(
      GuestHasPendingDebtException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffGuestHasPendingDebtResponse(
                "GUEST_HAS_PENDING_DEBT",
                "El huésped tiene deuda pendiente: salda sus períodos impagos antes de crear un contrato nuevo o reactivarlo",
                e.periods().stream().map(YearMonth::toString).toList(),
                e.debts().stream().map(this::debt).toList()));
  }

  /**
   * RN-12, RN-20, TAR-237: a contract or a stay holds the environment, and who. The texts are those
   * of the catalog of SRS 17: one for a contract or a deactivation, one for a stay (A3.5).
   */
  @ExceptionHandler(EnvironmentOccupiedException.class)
  public ResponseEntity<BffEnvironmentOccupiedResponse> handleEnvironmentOccupied(
      EnvironmentOccupiedException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffEnvironmentOccupiedResponse(
                "ENVIRONMENT_OCCUPIED",
                e.isForStay()
                    ? "El ambiente ya está ocupado en esas fechas"
                    : "Este ambiente está ocupado: finaliza primero su contrato",
                occupant(e.occupant())));
  }

  private DebtResponse debt(Debt debt) {
    YearMonth period = debt.period();
    Long stayId = debt.stayId();
    return new DebtResponse(
        period == null ? null : period.toString(),
        stayId == null ? null : ids.mask(IdKind.STAY, stayId),
        debt.stayStartDate(),
        debt.stayEndDate(),
        debt.amountDue());
  }

  private @Nullable OccupantResponse occupant(@Nullable Occupancy occupancy) {
    if (occupancy == null) {
      return null;
    }
    Long contractId = occupancy.contractId();
    Long stayId = occupancy.stayId();
    return new OccupantResponse(
        occupancy.kind().name(),
        ids.mask(IdKind.GUEST, occupancy.guestId()),
        guests.namesOf(Set.of(occupancy.guestId())).get(occupancy.guestId()),
        contractId == null ? null : ids.mask(IdKind.CONTRACT, contractId),
        stayId == null ? null : ids.mask(IdKind.STAY, stayId),
        occupancy.startDate(),
        occupancy.endDate());
  }
}
