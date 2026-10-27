package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase.AdministratorCreated;
import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase.CreateAdministratorCommand;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-22, CU-30: an administrator creates another one. Only ADMIN reaches /bff/admin/** (RN-08), and
 * a session pending its password change reaches none of it (RF-12); SecurityConfig enforces both.
 * The answer carries no password, code or link.
 */
@RestController
public class AdministratorsBffController {

  private static final String URL = "/bff/admin/administrators";

  private final CreateAdministratorUseCase administrators;
  private final IdMasker ids;

  public AdministratorsBffController(CreateAdministratorUseCase administrators, IdMasker ids) {
    this.administrators = administrators;
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
}
