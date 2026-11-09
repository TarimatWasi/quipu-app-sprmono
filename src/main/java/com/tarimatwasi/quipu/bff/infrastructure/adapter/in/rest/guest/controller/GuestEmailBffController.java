package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.GuestEmailVerificationUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.OtherGuestTypeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TAR-349: the guest changes its one email by proving it owns the new one. One route per role: the
 * contract guest and the temporary guest once active (the guard of the guest areas refuses the
 * other type and a guest still onboarding), and any guest during the onboarding (O1). The guest is
 * always the one of the session, never one named in the request (RN-09).
 */
@RestController
public class GuestEmailBffController {

  private final GuestEmailVerificationUseCase verification;
  private final GuestAccountUseCase accounts;

  public GuestEmailBffController(
      GuestEmailVerificationUseCase verification, GuestAccountUseCase accounts) {
    this.verification = verification;
    this.accounts = accounts;
  }

  /** EmailVerificationRequest: the new email; a field it does not define is refused. */
  public record EmailVerificationBody(
      @NotBlank @Email @Size(min = 3, max = 200) String email,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** EmailVerificationConfirmation: the six digits sent to the new email. */
  public record EmailConfirmationBody(
      @NotNull @Pattern(regexp = "[0-9]{6}") String code,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  @PostMapping({
    "/bff/guest/email-verification",
    "/bff/temporary-guest/email-verification",
    "/bff/guest/onboarding/email-verification"
  })
  public ResponseEntity<Void> request(
      @Valid @RequestBody EmailVerificationBody body, Authentication authentication) {
    GuestOnboardingBffController.rejectUnknown("", body.unknown());
    verification.request(guestOf(authentication), body.email().strip());
    return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
  }

  @PostMapping({
    "/bff/guest/email-verification/confirm",
    "/bff/temporary-guest/email-verification/confirm",
    "/bff/guest/onboarding/email-verification/confirm"
  })
  public ResponseEntity<Void> confirm(
      @Valid @RequestBody EmailConfirmationBody body, Authentication authentication) {
    GuestOnboardingBffController.rejectUnknown("", body.unknown());
    verification.confirm(guestOf(authentication), body.code());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  /** A GUEST account always belongs to a guest; one that does not is refused (RN-09). */
  private Long guestOf(Authentication authentication) {
    return accounts
        .guestOf(Long.valueOf(authentication.getName()))
        .orElseThrow(OtherGuestTypeException::new);
  }
}
