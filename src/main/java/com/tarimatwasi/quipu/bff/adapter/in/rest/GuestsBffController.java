package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestDetailView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.StatusFilter;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.UpdateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RF-02. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class GuestsBffController {

  private final ManageGuestsUseCase guests;
  private final IdMasker ids;

  public GuestsBffController(ManageGuestsUseCase guests, IdMasker ids) {
    this.guests = guests;
    this.ids = ids;
  }

  /** The contract requires {@code name} even when it is still null (RN-31). */
  public record GuestSummaryResponse(
      UUID id,
      String documentType,
      String documentNumber,
      @Nullable String name,
      String type,
      String status,
      boolean hasLoginAccess) {
    static GuestSummaryResponse of(GuestSummaryView guest, IdMasker ids) {
      return new GuestSummaryResponse(
          ids.mask(IdKind.GUEST, guest.id()),
          guest.documentType().name(),
          guest.documentNumber(),
          guest.name(),
          guest.type().name(),
          guest.status().name(),
          guest.hasLoginAccess());
    }
  }

  public record EmergencyContactResponse(
      @Nullable String name, @Nullable String relationship, @Nullable String phone) {
    static @Nullable EmergencyContactResponse of(@Nullable EmergencyContactView contact) {
      return contact == null
          ? null
          : new EmergencyContactResponse(contact.name(), contact.relationship(), contact.phone());
    }
  }

  /**
   * The optional fields are left out when there is none; the nullable ones the contract requires
   * ({@code name}) or defines as nullable are always there.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record GuestDetailResponse(
      UUID id,
      String documentType,
      String documentNumber,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String name,
      String type,
      String status,
      boolean hasLoginAccess,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String phone,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String contactEmail,
      @Nullable EmergencyContactResponse emergencyContact,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable BigDecimal agreedAmount,
      int documentCount,
      @JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Instant accessExpiresAt,
      String onboardingProgress,
      List<Object> paymentHistory) {
    static GuestDetailResponse of(GuestDetailView detail, IdMasker ids) {
      GuestSummaryView guest = detail.summary();
      return new GuestDetailResponse(
          ids.mask(IdKind.GUEST, guest.id()),
          guest.documentType().name(),
          guest.documentNumber(),
          guest.name(),
          guest.type().name(),
          guest.status().name(),
          guest.hasLoginAccess(),
          detail.phone(),
          detail.contactEmail(),
          EmergencyContactResponse.of(detail.emergencyContact()),
          detail.stayStartDate(),
          detail.stayEndDate(),
          detail.agreedAmount(),
          detail.documentCount(),
          detail.accessExpiresAt(),
          detail.onboardingProgress().name(),
          // The payments arrive with RF-04 to RF-06; until then the history is empty.
          List.of());
    }
  }

  /** The use case checks what the document type allows and the stay rules of each guest kind. */
  public record UpdateGuestRequest(
      @Nullable IdDocumentKind documentType,
      @Nullable @Size(min = 1, max = 20) String documentNumber,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable BigDecimal agreedAmount) {}

  @GetMapping("/bff/admin/guests")
  public List<GuestSummaryResponse> list(
      @RequestParam(name = "status", defaultValue = "CURRENT") StatusFilter status,
      @RequestParam(name = "type", required = false) @Nullable GuestKind type) {
    return guests.list(status, type).stream()
        .map(guest -> GuestSummaryResponse.of(guest, ids))
        .toList();
  }

  @GetMapping("/bff/admin/guests/{id}")
  public GuestDetailResponse get(@PathVariable UUID id) {
    return GuestDetailResponse.of(guests.get(ids.unmask(IdKind.GUEST, id)), ids);
  }

  /** RN-17, RN-36: only the document (while pending activation) and the stay data change here. */
  @PatchMapping("/bff/admin/guests/{id}")
  public GuestSummaryResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateGuestRequest request) {
    return GuestSummaryResponse.of(
        guests.update(
            ids.unmask(IdKind.GUEST, id),
            new UpdateCommand(
                request.documentType(),
                request.documentNumber(),
                request.stayStartDate(),
                request.stayEndDate(),
                request.agreedAmount())),
        ids);
  }
}
