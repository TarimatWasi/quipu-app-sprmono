package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.UnknownFieldException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.PagedResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.NewStay;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayChange;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.StayChargeView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
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

/**
 * RN-13, RN-17, CU-31; TAR-237: a stay occupies an environment during its dates. Only ADMIN reaches
 * /bff/admin/** (RN-08); SecurityConfig enforces it. The answer composes the stay (guest module),
 * the code of its environment (environment module) and its payment status (payment module).
 */
@RestController
public class StaysBffController {

  private final ManageStaysUseCase stays;
  private final ManageEnvironmentsUseCase environments;
  private final ManagePaymentsUseCase payments;
  private final IdMasker ids;

  public StaysBffController(
      ManageStaysUseCase stays,
      ManageEnvironmentsUseCase environments,
      ManagePaymentsUseCase payments,
      IdMasker ids) {
    this.stays = stays;
    this.environments = environments;
    this.payments = payments;
    this.ids = ids;
  }

  /**
   * The use case checks the order of the dates, the amount (greater than 0, two decimals) and that
   * the environment is active and free for the whole stay (RN-20, TAR-237).
   */
  public record StayRequest(
      @NotNull UUID environmentId,
      @NotNull LocalDate startDate,
      @NotNull LocalDate endDate,
      @NotNull BigDecimal agreedAmount) {}

  /**
   * The contract refuses any other field, so an unknown one is a 400 and not ignored. {@code
   * version} is the one the client loaded (TAR-276).
   */
  public record StayUpdateRequest(
      @NotNull @Min(0) Long version,
      @Nullable UUID environmentId,
      @Nullable LocalDate startDate,
      @Nullable LocalDate endDate,
      @Nullable BigDecimal agreedAmount,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** Stay of the contract; the nullable fields are always present. */
  public record StayResponse(
      UUID id,
      @Nullable UUID environmentId,
      @Nullable String environmentCode,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal agreedAmount,
      String paymentStatus,
      @Nullable UUID paymentId,
      long version) {}

  @GetMapping("/bff/admin/guests/{id}/stays")
  public PagedResponse<StayResponse> list(
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    // One lookup per environment of the page, not one per stay.
    Map<Long, String> codes = new HashMap<>();
    return PagedResponse.of(
        stays.list(ids.unmask(IdKind.GUEST, id), PageParams.query(after, limit)),
        stay -> response(stay, codes));
  }

  @PostMapping("/bff/admin/guests/{id}/stays")
  public ResponseEntity<StayResponse> create(
      @PathVariable UUID id, @Valid @RequestBody StayRequest request) {
    StayResponse created =
        response(
            stays.create(
                ids.unmask(IdKind.GUEST, id),
                new NewStay(
                    ids.unmask(IdKind.ENVIRONMENT, request.environmentId()),
                    request.startDate(),
                    request.endDate(),
                    request.agreedAmount())),
            new HashMap<>());
    return ResponseEntity.created(URI.create("/bff/admin/guests/" + id + "/stays/" + created.id()))
        .body(created);
  }

  @PatchMapping("/bff/admin/guests/{id}/stays/{stayId}")
  public StayResponse update(
      @PathVariable UUID id,
      @PathVariable UUID stayId,
      @Valid @RequestBody StayUpdateRequest request) {
    UnknownFieldException.rejectAny(request.unknown());
    UUID environment = request.environmentId();
    return response(
        stays.update(
            ids.unmask(IdKind.GUEST, id),
            ids.unmask(IdKind.STAY, stayId),
            new StayChange(
                request.version(),
                environment == null ? null : ids.unmask(IdKind.ENVIRONMENT, environment),
                request.startDate(),
                request.endDate(),
                request.agreedAmount())),
        new HashMap<>());
  }

  private StayResponse response(StayView stay, Map<Long, String> codes) {
    Long environmentId = stay.environmentId();
    StayChargeView charge = payments.stayCharge(stay.id(), stay.endDate());
    Long paymentId = charge.paymentId();
    return new StayResponse(
        ids.mask(IdKind.STAY, stay.id()),
        environmentId == null ? null : ids.mask(IdKind.ENVIRONMENT, environmentId),
        environmentId == null
            ? null
            : codes.computeIfAbsent(environmentId, key -> environments.get(key).code()),
        stay.startDate(),
        stay.endDate(),
        stay.agreedAmount(),
        charge.status(),
        paymentId == null ? null : ids.mask(IdKind.PAYMENT, paymentId),
        stay.version());
  }
}
