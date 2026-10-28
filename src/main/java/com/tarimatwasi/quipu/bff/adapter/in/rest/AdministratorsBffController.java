package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase.AdministratorCreated;
import com.tarimatwasi.quipu.auth.application.port.in.CreateAdministratorUseCase.CreateAdministratorCommand;
import com.tarimatwasi.quipu.auth.application.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorStatus;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.AdministratorView;
import com.tarimatwasi.quipu.auth.application.port.in.ManageAdministratorsUseCase.StatusChangeCommand;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-22, CU-30: an administrator creates another one, lists them and deactivates or reactivates
 * another one (TAR-127). Only ADMIN reaches /bff/admin/** (RN-08), and a session pending its
 * password change reaches none of it (RF-12); SecurityConfig enforces both. The answers carry no
 * password, code or link.
 */
@RestController
public class AdministratorsBffController {

  private static final String URL = "/bff/admin/administrators";

  private final CreateAdministratorUseCase administrators;
  private final ManageAdministratorsUseCase management;
  private final IdMasker ids;

  public AdministratorsBffController(
      CreateAdministratorUseCase administrators,
      ManageAdministratorsUseCase management,
      IdMasker ids) {
    this.administrators = administrators;
    this.management = management;
    this.ids = ids;
  }

  /** AdministratorInput of the contract; the use case checks the number against its type. */
  public record AdministratorRequest(
      @NotNull DocumentKind documentType,
      @NotBlank @Size(max = 20) String documentNumber,
      @NotBlank @Email @Size(max = 200) String email,
      @NotNull @Size(min = 1, max = 128) String currentPassword) {

    /** The password of the creator never reaches a log through the record. */
    @Override
    public String toString() {
      return "AdministratorRequest[documentType=" + documentType + "]";
    }
  }

  /** AdministratorCreated of the contract. */
  public record AdministratorCreatedResponse(UUID id, boolean emailSent) {}

  /** AdministratorReauthentication of the contract. */
  public record ReauthenticationRequest(@NotNull @Size(min = 1, max = 128) String currentPassword) {

    /** The password never reaches a log through the record. */
    @Override
    public String toString() {
      return "ReauthenticationRequest[]";
    }
  }

  /** AdministratorSummary of the contract; the document number comes masked. */
  public record AdministratorSummaryResponse(
      UUID id,
      DocumentKind documentType,
      String documentNumber,
      String email,
      AdministratorStatus status,
      Instant createdAt,
      boolean self) {}

  @PostMapping(URL)
  public ResponseEntity<AdministratorCreatedResponse> create(
      @Valid @RequestBody AdministratorRequest request, Authentication authentication) {
    AdministratorCreated created =
        administrators.create(
            new CreateAdministratorCommand(
                Long.valueOf(authentication.getName()),
                request.documentType(),
                request.documentNumber(),
                request.email(),
                request.currentPassword()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new AdministratorCreatedResponse(
                ids.mask(IdKind.USER, created.id()), created.emailSent()));
  }

  /** Personal data of privileged accounts: no cache may keep it. */
  @GetMapping(URL)
  public ResponseEntity<List<AdministratorSummaryResponse>> list(Authentication authentication) {
    List<AdministratorSummaryResponse> list =
        management.list(Long.valueOf(authentication.getName())).stream()
            .map(this::summary)
            .toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(list);
  }

  @PostMapping(URL + "/{id}/deactivate")
  public ResponseEntity<Void> deactivate(
      @PathVariable UUID id,
      @Valid @RequestBody ReauthenticationRequest request,
      Authentication authentication) {
    management.deactivate(command(id, request, authentication));
    return ResponseEntity.noContent().build();
  }

  @PostMapping(URL + "/{id}/reactivate")
  public ResponseEntity<Void> reactivate(
      @PathVariable UUID id,
      @Valid @RequestBody ReauthenticationRequest request,
      Authentication authentication) {
    management.reactivate(command(id, request, authentication));
    return ResponseEntity.noContent().build();
  }

  /**
   * An id never issued for an account goes on as none, so that it answers as an administrator that
   * does not exist only after the password is confirmed, like any other id.
   */
  private StatusChangeCommand command(
      UUID id, ReauthenticationRequest request, Authentication authentication) {
    return new StatusChangeCommand(
        Long.valueOf(authentication.getName()), administratorId(id), request.currentPassword());
  }

  private @Nullable Long administratorId(UUID id) {
    try {
      return ids.unmask(IdKind.USER, id);
    } catch (UnknownMaskedIdException e) {
      return null;
    }
  }

  private AdministratorSummaryResponse summary(AdministratorView view) {
    return new AdministratorSummaryResponse(
        ids.mask(IdKind.USER, view.id()),
        view.documentType(),
        view.maskedDocumentNumber(),
        view.email(),
        view.status(),
        view.createdAt(),
        view.self());
  }
}
