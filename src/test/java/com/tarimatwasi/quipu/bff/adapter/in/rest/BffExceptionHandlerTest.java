package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class BffExceptionHandlerTest {

  private static final IdMasker MASKER =
      new IdMasker(new IdMaskProperties("test-secret-test-secret-test-secret-00"));

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

  /** TAR-182: the errors of the stays, with the codes of the contract. */
  @Test
  void stayErrors_answerTheCodesOfTheContract() {
    assertThat(handler.handleStayNotFound().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(handler.handleStayNotFound().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("STAY_NOT_FOUND");
    assertThat(handler.handleStayOverlap().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(handler.handleStayOverlap().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("STAY_OVERLAP");
    assertThat(handler.handleGuestNotTemporary().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(handler.handleGuestNotTemporary().getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("GUEST_NOT_TEMPORARY");
  }

  @Test
  void guestNotActive_namesTheGuestWithItsMaskedId() {
    var response = handler.handleGuestNotActive(new GuestNotActiveException(42L));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new BffGuestNotActiveResponse(
                "GUEST_NOT_ACTIVE", "El huésped no está activo", MASKER.mask(IdKind.GUEST, 42L)));
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
                "El contrato ya no cobra el período de este pago; solo puedes rechazarlo indicando"
                    + " el motivo"));
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
