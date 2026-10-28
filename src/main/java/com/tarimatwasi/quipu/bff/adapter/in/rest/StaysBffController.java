package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.NewStay;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayChange;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RN-13, RN-17, CU-31. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class StaysBffController {

  private final ManageStaysUseCase stays;
  private final IdMasker ids;

  public StaysBffController(ManageStaysUseCase stays, IdMasker ids) {
    this.stays = stays;
    this.ids = ids;
  }

  /** The use case checks the order of the dates and the amount (greater than 0, two decimals). */
  public record StayRequest(
      @NotNull LocalDate startDate, @NotNull LocalDate endDate, @NotNull BigDecimal agreedAmount) {}

  /** The contract refuses any other field, so an unknown one is a 400 and not ignored. */
  public record StayUpdateRequest(
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable BigDecimal agreedAmount,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  public record StayResponse(
      UUID id, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {
    static StayResponse of(StayView stay, IdMasker ids) {
      return new StayResponse(
          ids.mask(IdKind.STAY, stay.id()), stay.startDate(), stay.endDate(), stay.agreedAmount());
    }
  }

  @GetMapping("/bff/admin/guests/{id}/stays")
  public PagedResponse<StayResponse> list(
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    return PagedResponse.of(
        stays.list(ids.unmask(IdKind.GUEST, id), PageParams.query(after, limit)),
        stay -> StayResponse.of(stay, ids));
  }

  @PostMapping("/bff/admin/guests/{id}/stays")
  public ResponseEntity<StayResponse> create(
      @PathVariable UUID id, @Valid @RequestBody StayRequest request) {
    StayResponse created =
        StayResponse.of(
            stays.create(
                ids.unmask(IdKind.GUEST, id),
                new NewStay(request.startDate(), request.endDate(), request.agreedAmount())),
            ids);
    return ResponseEntity.created(URI.create("/bff/admin/guests/" + id + "/stays/" + created.id()))
        .body(created);
  }

  @PatchMapping("/bff/admin/guests/{id}/stays/{stayId}")
  public StayResponse update(
      @PathVariable UUID id,
      @PathVariable UUID stayId,
      @Valid @RequestBody StayUpdateRequest request) {
    UnknownFieldException.rejectAny(request.unknown());
    return StayResponse.of(
        stays.update(
            ids.unmask(IdKind.GUEST, id),
            ids.unmask(IdKind.STAY, stayId),
            new StayChange(request.startDate(), request.endDate(), request.agreedAmount())),
        ids);
  }
}
