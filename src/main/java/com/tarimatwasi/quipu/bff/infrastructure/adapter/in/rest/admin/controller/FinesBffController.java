package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentLabel;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineCommand;
import com.tarimatwasi.quipu.fine.application.port.in.ManageFinesUseCase.FineView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestRef;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-19, RN-30. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class FinesBffController {

  private static final String URL = "/bff/admin/fines";

  // PostgreSQL cannot store the NUL character in a TEXT column
  private static final String NO_NUL = "(?s)[^\\x00]*";

  private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

  /** Read as text so that a value that is not a UUID is a 400 that names the field. */
  private static final String UUID_TEXT =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final java.util.regex.Pattern MONTH_PATTERN =
      java.util.regex.Pattern.compile(MONTH);

  private final ManageFinesUseCase fines;
  private final ManageGuestsUseCase guests;
  private final ManageEnvironmentsUseCase environments;
  private final IdMasker ids;

  public FinesBffController(
      ManageFinesUseCase fines,
      ManageGuestsUseCase guests,
      ManageEnvironmentsUseCase environments,
      IdMasker ids) {
    this.fines = fines;
    this.guests = guests;
    this.environments = environments;
    this.ids = ids;
  }

  /** The use case checks the amount: greater than 0, at most two decimals and the column limit. */
  public record FineRequest(
      @NotNull @Pattern(regexp = UUID_TEXT) String guestId,
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = MONTH) String month,
      @Nullable @Pattern(regexp = NO_NUL) String description) {}

  /**
   * The description is left out when there is none; the guest's name and environment are always
   * written, null when unknown (TAR-240).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FineResponse(
      UUID id,
      UUID guestId,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String guestName,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String environmentCode,
      BigDecimal amount,
      String month,
      @Nullable String description) {}

  @GetMapping(URL)
  public List<FineResponse> list(@RequestParam("month") String month) {
    if (!MONTH_PATTERN.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    YearMonth asked = YearMonth.parse(month);
    return responses(fines.list(asked), asked);
  }

  @PostMapping(URL)
  public ResponseEntity<FineResponse> create(@Valid @RequestBody FineRequest request) {
    YearMonth month = YearMonth.parse(request.month());
    FineView fine =
        fines.create(
            new FineCommand(
                guestOf(request.guestId()), request.amount(), month, request.description()));
    FineResponse created = responses(List.of(fine), month).getFirst();
    return ResponseEntity.created(URI.create(URL + "/" + created.id())).body(created);
  }

  @DeleteMapping(URL + "/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    fines.delete(ids.unmask(IdKind.FINE, id));
  }

  /**
   * TAR-240 (A6 «Multas»): the guest's name and the environment it held in the month of the fine,
   * else its last one.
   */
  private List<FineResponse> responses(List<FineView> found, YearMonth month) {
    Set<Long> guestIds = new HashSet<>();
    found.forEach(fine -> guestIds.add(fine.guestId()));
    Map<Long, GuestRef> refs = guests.refsOf(guestIds, month.atDay(1), month.atEndOfMonth());
    Set<Long> environmentIds = new HashSet<>();
    for (GuestRef ref : refs.values()) {
      Occupancy environment = ref.environment();
      if (environment != null) {
        environmentIds.add(environment.environmentId());
      }
    }
    Map<Long, EnvironmentLabel> labels = environments.labelsOf(environmentIds);
    return found.stream()
        .map(
            fine -> {
              GuestRef ref = refs.get(fine.guestId());
              Occupancy environment = ref == null ? null : ref.environment();
              EnvironmentLabel label =
                  environment == null ? null : labels.get(environment.environmentId());
              return new FineResponse(
                  ids.mask(IdKind.FINE, fine.id()),
                  ids.mask(IdKind.GUEST, fine.guestId()),
                  ref == null ? null : ref.name(),
                  label == null ? null : label.code(),
                  fine.amount(),
                  fine.month().toString(),
                  fine.description());
            })
        .toList();
  }

  /**
   * A guest id that was never issued is passed as none: the use case answers it as a guest that
   * does not exist, after it checked the rest of the data.
   */
  private @Nullable Long guestOf(String masked) {
    try {
      return ids.unmask(IdKind.GUEST, UUID.fromString(masked));
    } catch (UnknownMaskedIdException forged) {
      return null;
    }
  }
}
