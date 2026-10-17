package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.auth.port.in.ActivateAccessUseCase;
import com.tarimatwasi.quipu.auth.port.in.ActivateAccessUseCase.ActivationResult;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase.ChangePasswordCommand;
import com.tarimatwasi.quipu.auth.port.in.ChangePasswordUseCase.ChangePasswordResult;
import com.tarimatwasi.quipu.auth.port.in.CurrentSessionUseCase;
import com.tarimatwasi.quipu.auth.port.in.CurrentSessionUseCase.CurrentSession;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.LoginCommand;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.LoginResult;
import com.tarimatwasi.quipu.auth.port.in.PasswordRecoveryUseCase;
import com.tarimatwasi.quipu.guest.port.in.GuestSessionUseCase;
import com.tarimatwasi.quipu.guest.port.in.GuestSessionUseCase.GuestSessionView;
import com.tarimatwasi.quipu.guest.port.in.GuestSessionUseCase.OnboardingStep;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthBffController {

  private final LoginUseCase loginUseCase;
  private final ChangePasswordUseCase changePasswordUseCase;
  private final CurrentSessionUseCase currentSessionUseCase;
  private final PasswordRecoveryUseCase passwordRecoveryUseCase;
  private final ActivateAccessUseCase activateAccessUseCase;
  private final GuestSessionUseCase guestSessions;
  private final SessionCookies sessionCookies;

  public AuthBffController(
      LoginUseCase loginUseCase,
      ChangePasswordUseCase changePasswordUseCase,
      CurrentSessionUseCase currentSessionUseCase,
      PasswordRecoveryUseCase passwordRecoveryUseCase,
      ActivateAccessUseCase activateAccessUseCase,
      GuestSessionUseCase guestSessions,
      SessionCookies sessionCookies) {
    this.loginUseCase = loginUseCase;
    this.changePasswordUseCase = changePasswordUseCase;
    this.currentSessionUseCase = currentSessionUseCase;
    this.passwordRecoveryUseCase = passwordRecoveryUseCase;
    this.activateAccessUseCase = activateAccessUseCase;
    this.guestSessions = guestSessions;
    this.sessionCookies = sessionCookies;
  }

  // TAR-179 (SEC-01): every free-text field is capped. The contract declares no maxLength
  // here; documents are at most 20 characters (as in the guest endpoints), the password cap is
  // above PasswordPolicy's 72 bytes, and the recovery code is 256 bits in Base64Url (~43).
  private static final int MAX_DOCUMENT = 20;
  private static final int MAX_PASSWORD = 128;
  private static final int MAX_CODE = 100;

  public record LoginRequest(
      @NotNull DocumentKind documentType,
      @NotBlank @Size(max = MAX_DOCUMENT) String documentNumber,
      @NotBlank @Size(max = MAX_PASSWORD) String password) {}

  /**
   * SessionInfo of the contract: the administrator carries only the first three fields; a guest
   * also its type, its state and, while onboarding, the step to go to (PASSWORD while a temporary
   * password has not been replaced, RF-12).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SessionResponse(
      String role,
      @Nullable String name,
      boolean mustChangePassword,
      @Nullable String guestType,
      @Nullable String guestStatus,
      @Nullable String nextOnboardingStep) {}

  public record ActivateRequest(
      @NotBlank @Size(max = MAX_CODE) String token,
      @NotNull @Size(max = MAX_PASSWORD) String newPassword) {}

  public record ChangePasswordRequest(
      @Nullable @Size(max = MAX_PASSWORD) String currentPassword,
      @NotNull @Size(max = MAX_PASSWORD) String newPassword) {}

  public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 200) String email) {}

  public record ResetPasswordRequest(
      @NotBlank @Size(max = MAX_CODE) String code,
      @NotNull @Size(max = MAX_PASSWORD) String newPassword) {}

  @PostMapping("/bff/auth/login")
  public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
    LoginResult result =
        loginUseCase.login(
            new LoginCommand(request.documentType(), request.documentNumber(), request.password()));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookies.session(result.sessionToken()).toString())
        .body(
            session(
                result.role(),
                result.displayEmail(),
                result.mustChangePassword(),
                result.guestId()));
  }

  /**
   * RF-11, CU-24: the single-use link sets the password and opens the session at once. The token is
   * never logged nor echoed.
   */
  @PostMapping("/bff/auth/activate")
  public ResponseEntity<SessionResponse> activate(@Valid @RequestBody ActivateRequest request) {
    ActivationResult result =
        activateAccessUseCase.activate(request.token(), request.newPassword());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookies.session(result.sessionToken()).toString())
        .body(session("GUEST", null, false, result.guestId()));
  }

  /** TAR-74: restores the session after a reload; answers the account as it is now. */
  @GetMapping("/bff/auth/me")
  public SessionResponse me(Authentication authentication) {
    CurrentSession current = currentSessionUseCase.currentSession(authentication.getName());
    return session(
        current.role(),
        current.displayEmail(),
        current.mustChangePassword() || isTemporary(authentication),
        current.guestId());
  }

  /** The access that opened a temporary session, signed in its token; null for any other. */
  private static @Nullable Long temporaryGrantOf(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return null;
    }
    String grant = token.getToken().getClaimAsString(AccessSessions.TEMPORARY_CLAIM);
    try {
      return grant == null ? null : Long.valueOf(grant);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The session opened with a temporary password (RF-12); see {@link AccessSessions}. */
  private static boolean isTemporary(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(a -> AccessSessions.TEMPORARY_AUTHORITY.equals(a.getAuthority()));
  }

  private SessionResponse session(
      String role, @Nullable String name, boolean mustChangePassword, @Nullable Long guestId) {
    if (guestId == null) {
      return new SessionResponse(role, name, mustChangePassword, null, null, null);
    }
    GuestSessionView guest = guestSessions.sessionOf(guestId);
    // D-30: a guest pending activation that signed in with its temporary password is still stored
    // as pending until it chooses its password; its session is the password step of the onboarding.
    boolean passwordStep =
        mustChangePassword
            && (guest.status() == GuestState.PENDING_ACTIVATION
                || guest.status() == GuestState.ONBOARDING);
    OnboardingStep next = guest.nextStep();
    String step;
    if (passwordStep) {
      step = "PASSWORD";
    } else {
      step = guest.status() == GuestState.ONBOARDING && next != null ? next.name() : null;
    }
    String status = passwordStep ? GuestState.ONBOARDING.name() : guest.status().name();
    return new SessionResponse(
        role, guest.name(), mustChangePassword, guest.type().name(), status, step);
  }

  /**
   * TAR-74: the JWT cannot be revoked, so logging out expires the browser's cookie (same path and
   * flags as the login). Needs a session, as the contract says.
   */
  @PostMapping("/bff/auth/logout")
  public ResponseEntity<Void> logout() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, sessionCookies.expired().toString())
        .build();
  }

  /** RF-12: the change replaces the session cookie with one that no longer forces the change. */
  @PostMapping("/bff/auth/change-password")
  public ResponseEntity<Void> changePassword(
      @Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
    ChangePasswordResult result =
        changePasswordUseCase.changePassword(
            new ChangePasswordCommand(
                authentication.getName(),
                request.currentPassword(),
                request.newPassword(),
                temporaryGrantOf(authentication)));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, sessionCookies.session(result.sessionToken()).toString())
        .build();
  }

  /** RF-16: always 202, whether the email belongs to an account or not. */
  @PostMapping("/bff/auth/forgot-password")
  public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
    passwordRecoveryUseCase.requestReset(request.email());
    return ResponseEntity.accepted().build();
  }

  /** RF-16: the person signs in afterwards, so no session is issued here. */
  @PostMapping("/bff/auth/reset-password")
  public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    passwordRecoveryUseCase.resetPassword(request.code(), request.newPassword());
    return ResponseEntity.noContent().build();
  }
}
