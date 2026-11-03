package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The 409 ENVIRONMENT_OCCUPIED with who holds the environment, for the dialog and its link
 * (TAR-237); {@code occupant} is left out when it is not known (an inactive environment).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BffEnvironmentOccupiedResponse(
    String code, String message, @Nullable OccupantResponse occupant) {

  /** Occupant of the contract; its nullable fields are always present. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record OccupantResponse(
      String kind,
      UUID guestId,
      @Nullable String guestName,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      LocalDate startDate,
      @Nullable LocalDate endDate) {}
}
