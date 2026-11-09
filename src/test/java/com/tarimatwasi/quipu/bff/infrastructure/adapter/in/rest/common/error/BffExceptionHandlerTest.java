package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.application.port.in.AccountLockedException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.auth.error.BffAccountLockedResponse;
import com.tarimatwasi.quipu.guest.application.port.in.GuestHasActiveContractException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestInactiveException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.application.port.in.StayOverlapException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class BffExceptionHandlerTest {

  private static final IdMaskProperties KEY =
      new IdMaskProperties("test-secret-test-secret-test-secret-00");

  /** The session of the request being answered (TAR-227). */
  private static final MaskSession SESSION =
      new IdMasker(KEY, Optional::empty).session("AAECAwQFBgcICQoLDA0ODw");

  private static final IdMasker MASKER = new IdMasker(KEY, () -> Optional.of(SESSION));

  private final BffExceptionHandler handler = new BffExceptionHandler(MASKER);

  /** BE-SPR-DAT-13, QP-SPRMONO-BFF-01 (TAR-171): code in English, message in Spanish. */
  @Test
  void concurrentModification_answers409WithTheBffErrorFormat() {
    ResponseEntity<BffErrorResponse> response = handler.handleConcurrentModification();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "CONCURRENT_MODIFICATION",
                "El registro cambió mientras lo editabas; vuelve a cargarlo e inténtalo de nuevo"));
  }

  // TAR-247: the screens show the `message` of the BFF (QP-UI-17), so it is the literal text of
  // the error catalog of «SRS 17 - Árbol de Flujo de Pantallas». One test per code.

  @Test
  void documentInvalidFile_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleInvalidOnboardingInput(
            new InvalidOnboardingInputException(
                InvalidOnboardingInputException.Reason.DOCUMENT_INVALID_FILE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "DOCUMENT_INVALID_FILE",
                "Archivo inválido — sube JPG, PNG o PDF de máx. 5MB",
                "file"));
  }

  @Test
  void documentLimitReached_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleOnboardingConflict(
            new OnboardingConflictException(
                OnboardingConflictException.Reason.DOCUMENT_LIMIT_REACHED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse("DOCUMENT_LIMIT_REACHED", "Ya subiste el máximo de 5 documentos"));
  }

  @Test
  void consentNotAccepted_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleInvalidOnboardingInput(
            new InvalidOnboardingInputException(
                InvalidOnboardingInputException.Reason.CONSENT_NOT_ACCEPTED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "CONSENT_NOT_ACCEPTED",
                "Debes marcar el consentimiento de datos personales",
                "accepted"));
  }

  @Test
  void temporaryPasswordExpired_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleTemporaryPasswordExpired();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "AUTH_TEMPORARY_PASSWORD_EXPIRED",
                "La clave temporal venció, pide una nueva al administrador"));
  }

  @Test
  void accountDisabled_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleAccountDisabled();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "AUTH_ACCOUNT_DISABLED",
                "Tu cuenta está deshabilitada. Contacta al administrador."));
  }

  @Test
  void onboardingRequired_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handleOnboardingRequired();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "AUTH_ONBOARDING_REQUIRED", "Debes completar tu registro para continuar"));
  }

  @Test
  void guestInactive_answersTheCatalogText() {
    var response = handler.handleGuestInactive(new GuestInactiveException(42L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffGuestNotActiveResponse(
                "GUEST_INACTIVE", "Este huésped está inactivo", MASKER.mask(IdKind.GUEST, 42L)));
  }

  @Test
  void guestHasActiveContract_answersTheCatalogText() {
    var response = handler.handleGuestHasActiveContract(new GuestHasActiveContractException(9L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffGuestHasActiveContractResponse(
                "GUEST_HAS_ACTIVE_CONTRACT",
                "Este huésped tiene un contrato vigente: finalízalo primero",
                MASKER.mask(IdKind.CONTRACT, 9L)));
  }

  @Test
  void onboardingAlreadyCompleted_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleOnboardingConflict(
            new OnboardingConflictException(OnboardingConflictException.Reason.ALREADY_COMPLETED));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse("ONBOARDING_ALREADY_COMPLETED", "Tu registro ya está completo"));
  }

  @Test
  void onboardingStepOutOfOrder_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response =
        handler.handleOnboardingConflict(
            new OnboardingConflictException(OnboardingConflictException.Reason.STEP_OUT_OF_ORDER));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "ONBOARDING_STEP_OUT_OF_ORDER", "Completa primero el paso anterior"));
  }

  @Test
  void paymentReasonRequired_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handlePaymentReasonRequired();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "PAYMENT_REASON_REQUIRED", "El motivo de rechazo es obligatorio", "reason"));
  }

  @Test
  void paymentAlreadyResolved_answersTheCatalogText() {
    ResponseEntity<BffErrorResponse> response = handler.handlePaymentAlreadyResolved();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "PAYMENT_ALREADY_RESOLVED", "Este pago ya fue resuelto por otro proceso"));
  }

  /** TAR-182: the errors of the stays, with the codes of the contract. */
  @Test
  void stayErrors_answerTheCodesOfTheContract() {
    assertThat(handler.handleStayNotFound().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(handler.handleStayNotFound().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("STAY_NOT_FOUND");
    assertThat(handler.handleGuestNotTemporary().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(handler.handleGuestNotTemporary().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("GUEST_NOT_TEMPORARY");
  }

  /** TAR-247: the dates of the stay of the same guest that is crossed, as dd/MM/yyyy. */
  @Test
  void stayOverlap_namesTheDatesOfTheCrossedStay() {
    var response =
        handler.handleStayOverlap(
            new StayOverlapException(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 11, 20)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "STAY_OVERLAP",
                "Estas fechas se cruzan con otra estadía del huésped: del 05/03/2026 al"
                    + " 20/11/2026"));
  }

  /** TAR-247: the administration reads the text for whoever manages the guest. */
  @Test
  void guestNotActive_toTheAdministration_namesTheGuestWithItsMaskedId() {
    var response =
        handler.handleGuestNotActive(
            new GuestNotActiveException(42L), request("/bff/admin/payments"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffGuestNotActiveResponse(
                "GUEST_NOT_ACTIVE",
                "Este huésped aún no está activo: no admite contratos ni pagos",
                MASKER.mask(IdKind.GUEST, 42L)));
  }

  /** TAR-247: the guest with a contract (H3) and the temporary one (T2) read their own text. */
  @Test
  void guestNotActive_toTheGuest_tellsItToAskTheAdministration() {
    for (String path : List.of("/bff/guest/payments", "/bff/temporary-guest/payments")) {
      var response = handler.handleGuestNotActive(new GuestNotActiveException(42L), request(path));

      assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.CONFLICT);
      assertThat(response.getBody())
          .as(path)
          .isEqualTo(
              new BffGuestNotActiveResponse(
                  "GUEST_NOT_ACTIVE",
                  "No puedes enviar pagos mientras tu cuenta no esté activa. Consulta con la"
                      + " administración.",
                  MASKER.mask(IdKind.GUEST, 42L)));
    }
  }

  /**
   * TAR-247: the time left (rounded up, as {@code Retry-After}) and the hour of Lima the lock ends
   * at. 10:32 in Lima is 15:32 UTC.
   */
  @Test
  void accountLocked_saysTheTimeLeftAndTheHourOfLima() {
    Instant until = Instant.parse("2026-11-03T15:32:00Z");

    var response =
        handler.handleAccountLocked(
            new AccountLockedException(Instant.parse("2026-11-03T15:17:55Z"), until));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("845");
    assertThat(response.getBody())
        .isEqualTo(
            new BffAccountLockedResponse(
                "AUTH_ACCOUNT_LOCKED",
                "Cuenta bloqueada por intentos fallidos. Podrás intentarlo de nuevo en 14:05, a"
                    + " las 10:32 (hora de Lima)",
                until));
  }

  /** TAR-247: a part of a second left counts as a whole one, like {@code Retry-After}. */
  @Test
  void accountLocked_roundsTheTimeLeftUpToTheSecond() {
    Instant until = Instant.parse("2026-11-03T15:32:00Z");

    var response =
        handler.handleAccountLocked(
            new AccountLockedException(Instant.parse("2026-11-03T15:17:54.001Z"), until));

    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("846");
    assertThat(response.getBody())
        .extracting(BffAccountLockedResponse::message)
        .isEqualTo(
            "Cuenta bloqueada por intentos fallidos. Podrás intentarlo de nuevo en 14:06, a"
                + " las 10:32 (hora de Lima)");
  }

  /** TAR-247: the hour is the one of Lima even when UTC is already the next day. */
  @Test
  void accountLocked_saysTheHourOfLimaWhenUtcIsAnotherDay() {
    Instant until = Instant.parse("2026-11-04T02:10:00Z");

    var response =
        handler.handleAccountLocked(
            new AccountLockedException(Instant.parse("2026-11-04T01:55:00Z"), until));

    assertThat(response.getBody())
        .extracting(BffAccountLockedResponse::message)
        .isEqualTo(
            "Cuenta bloqueada por intentos fallidos. Podrás intentarlo de nuevo en 15:00, a"
                + " las 21:10 (hora de Lima)");
  }

  private static MockHttpServletRequest request(String path) {
    return new MockHttpServletRequest("POST", path);
  }

  @Test
  void aForgedStayIdAnswersAsAMissingStay() {
    var response = handler.handleUnknownMaskedId(new UnknownMaskedIdException(IdKind.STAY));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).extracting(BffErrorResponse::code).isEqualTo("STAY_NOT_FOUND");
  }

  /** TAR-47: a forged fine id answers as the missing fine, with the code of the contract. */
  @Test
  void aForgedFineIdAnswersAsAMissingFine() {
    var response = handler.handleUnknownMaskedId(new UnknownMaskedIdException(IdKind.FINE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .isEqualTo(new BffErrorResponse("RESOURCE_NOT_FOUND", "La multa no existe"));
  }

  /**
   * TAR-43: losing the race for a payment is "already resolved"; for anything else it is a
   * conflict.
   */
  @Test
  void aVersionConflictOnAPaymentIsAlreadyResolved() {
    var payment =
        new org.springframework.orm.ObjectOptimisticLockingFailureException(
            "com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence.PaymentJpaEntity",
            7L);
    var other =
        new org.springframework.orm.ObjectOptimisticLockingFailureException(
            "com.tarimatwasi.quipu.contract.infrastructure.adapter.out.persistence.ContractJpaEntity",
            7L);

    assertThat(handler.handleObjectConcurrentModification(payment).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("PAYMENT_ALREADY_RESOLVED");
    assertThat(handler.handleObjectConcurrentModification(payment).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(handler.handleObjectConcurrentModification(other).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("CONCURRENT_MODIFICATION");
  }

  /** TAR-207: a period the contract no longer charges has its own code (contract v0.9.2). */
  @Test
  void aPeriodNotPayableHasItsOwnCode() {
    var response = handler.handlePaymentPeriodNotPayable();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffErrorResponse(
                "PAYMENT_PERIOD_NOT_PAYABLE",
                "Este mes ya no se cobra: el contrato terminó antes de aprobar este pago."
                    + " Recházalo con un motivo para que el huésped lo sepa."));
    assertThat(handler.handlePaymentAlreadyResolved().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("PAYMENT_ALREADY_RESOLVED");
  }

  /** Throws whatever the test hands it, so the advice is reached the way a request reaches it. */
  @RestController
  static class ThrowingController {

    private Supplier<RuntimeException> failure = IllegalStateException::new;

    @GetMapping("/fail")
    void fail() {
      throw failure.get();
    }
  }

  private static MockMvc mvcThrowing(Supplier<RuntimeException> failure) {
    var controller = new ThrowingController();
    controller.failure = failure;
    return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(HANDLER).build();
  }

  private static final BffExceptionHandler HANDLER = new BffExceptionHandler(MASKER);

  /**
   * TAR-204: a row lock that waits past lock_timeout ends the request as the concurrency conflict
   * of the contract, whether it comes translated by Spring or straight from JPA; never a 500.
   */
  @Test
  void aLockThatTimesOutIsAConcurrencyConflict() throws Exception {
    for (Supplier<RuntimeException> failure :
        List.<Supplier<RuntimeException>>of(
            () -> new CannotAcquireLockException("lock timeout"),
            () -> new LockTimeoutException("lock timeout"),
            () -> new PessimisticLockException("lock timeout"))) {
      mvcThrowing(failure)
          .perform(get("/fail"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
          .andExpect(
              jsonPath("$.message")
                  .value(
                      "El registro cambió mientras lo editabas; vuelve a cargarlo e inténtalo de"
                          + " nuevo"));
    }
  }

  /** TAR-204: R2 that does not answer in time is the 503 of the contract, without the cause. */
  @Test
  void aStorageTimeoutIsFileStorageUnavailable() throws Exception {
    mvcThrowing(() -> new PaymentFileUnavailableException(new SocketTimeoutException("r2 slow")))
        .perform(get("/fail"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.message")
                .value("No se pudo acceder al archivo; inténtalo de nuevo más tarde"));
  }
}
