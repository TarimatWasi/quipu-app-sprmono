package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.bff.adapter.in.rest.GuestsBffController.GuestSummaryResponse;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase.AccessView;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase.RegisterCommand;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestAccessUseCase.Registration;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-02, RF-11, RF-17: registering a guest and generating its access. Only ADMIN reaches
 * /bff/admin/** (RN-08). The answers that carry an access in clear are never cacheable.
 */
@RestController
public class GuestAccessBffController {

  private final ManageGuestAccessUseCase access;
  private final IdMasker ids;

  public GuestAccessBffController(ManageGuestAccessUseCase access, IdMasker ids) {
    this.access = access;
    this.ids = ids;
  }

  /** GuestInput of the contract (RN-31). */
  public record CreateGuestRequest(
      @NotNull IdDocumentKind documentType,
      @NotBlank @Size(max = 20) String documentNumber,
      @NotNull GuestKind type,
      @Nullable @Size(max = 30) String phone,
      @Nullable @Email @Size(max = 200) String contactEmail,
      @NotNull AccessMode accessMode) {}

  public record GenerateAccessRequest(@NotNull AccessMode accessMode) {}

  /** AccessGrant: the secret goes in the field of its mode only. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AccessGrantResponse(
      String mode,
      @Nullable String activationUrl,
      @Nullable String temporaryPassword,
      Instant expiresAt,
      boolean emailSent,
      String shareMessage) {
    static AccessGrantResponse of(AccessView view) {
      boolean link = view.mode() == AccessMode.LINK;
      return new AccessGrantResponse(
          view.mode().name(),
          link ? view.secret() : null,
          link ? null : view.secret(),
          view.expiresAt(),
          view.emailSent(),
          view.shareMessage());
    }

    @Override
    public String toString() {
      // Never the secret: this record may reach a log by accident.
      return "AccessGrantResponse[mode=" + mode + ", expiresAt=" + expiresAt + "]";
    }
  }

  /** GuestCreated (201, with access) or GuestAlreadyRegistered (200, without it). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RegistrationResponse(
      GuestSummaryResponse guest,
      boolean alreadyRegistered,
      @Nullable AccessGrantResponse access) {}

  @PostMapping("/bff/admin/guests")
  public ResponseEntity<RegistrationResponse> create(
      @Valid @RequestBody CreateGuestRequest request) {
    Registration registration =
        access.register(
            new RegisterCommand(
                request.documentType(),
                request.documentNumber(),
                request.type(),
                request.phone(),
                request.contactEmail(),
                request.accessMode()));
    GuestSummaryResponse guest = GuestSummaryResponse.of(registration.guest(), ids);
    AccessView issued = registration.access();
    if (issued == null) {
      return ResponseEntity.ok(new RegistrationResponse(guest, true, null));
    }
    return noStore(ResponseEntity.created(URI.create("/bff/admin/guests/" + guest.id())))
        .body(new RegistrationResponse(guest, false, AccessGrantResponse.of(issued)));
  }

  @PostMapping("/bff/admin/guests/{id}/access")
  public ResponseEntity<AccessGrantResponse> generate(
      @PathVariable UUID id, @Valid @RequestBody GenerateAccessRequest request) {
    AccessView issued = access.generateAccess(ids.unmask(IdKind.GUEST, id), request.accessMode());
    return noStore(ResponseEntity.ok()).body(AccessGrantResponse.of(issued));
  }

  /** Exactly {@code no-store}, as the contract says, instead of Spring Security's default list. */
  private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder builder) {
    return builder.cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache");
  }
}
