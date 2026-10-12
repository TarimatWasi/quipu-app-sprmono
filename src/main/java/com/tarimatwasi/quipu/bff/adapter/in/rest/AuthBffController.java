package com.tarimatwasi.quipu.bff.adapter.in.rest;

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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
  private final SessionCookies sessionCookies;

  public AuthBffController(
      LoginUseCase loginUseCase,
      ChangePasswordUseCase changePasswordUseCase,
      CurrentSessionUseCase currentSessionUseCase,
      PasswordRecoveryUseCase passwordRecoveryUseCase,
      SessionCookies sessionCookies) {
    this.loginUseCase = loginUseCase;
    this.changePasswordUseCase = changePasswordUseCase;
    this.currentSessionUseCase = currentSessionUseCase;
    this.passwordRecoveryUseCase = passwordRecoveryUseCase;
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

  public record LoginResponse(String role, String name, boolean mustChangePassword) {}

  public record CurrentSessionResponse(String role, String name, boolean mustChangePassword) {}

  public record ChangePasswordRequest(
      @Nullable @Size(max = MAX_PASSWORD) String currentPassword,
      @NotNull @Size(max = MAX_PASSWORD) String newPassword) {}

  public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 200) String email) {}

  public record ResetPasswordRequest(
      @NotBlank @Size(max = MAX_CODE) String code,
      @NotNull @Size(max = MAX_PASSWORD) String newPassword) {}

  @PostMapping("/bff/auth/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
    LoginResult result =
        loginUseCase.login(
            new LoginCommand(request.documentType(), request.documentNumber(), request.password()));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookies.session(result.sessionToken()).toString())
        .body(new LoginResponse(result.role(), result.displayEmail(), result.mustChangePassword()));
  }

  /** TAR-74: restores the session after a reload; answers the account as it is now. */
  @GetMapping("/bff/auth/me")
  public CurrentSessionResponse me(Authentication authentication) {
    CurrentSession session = currentSessionUseCase.currentSession(authentication.getName());
    return new CurrentSessionResponse(
        session.role(), session.displayEmail(), session.mustChangePassword());
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
                authentication.getName(), request.currentPassword(), request.newPassword()));
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
