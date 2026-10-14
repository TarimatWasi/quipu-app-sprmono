package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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

  /**
   * TAR-43: losing the race for a payment is "already resolved"; for anything else it is a
   * conflict.
   */
  @Test
  void aVersionConflictOnAPaymentIsAlreadyResolved() {
    var payment =
        new org.springframework.orm.ObjectOptimisticLockingFailureException(
            "com.tarimatwasi.quipu.payment.adapter.out.persistence.PaymentJpaEntity", 7L);
    var other =
        new org.springframework.orm.ObjectOptimisticLockingFailureException(
            "com.tarimatwasi.quipu.contract.adapter.out.persistence.ContractJpaEntity", 7L);

    assertThat(handler.handleObjectConcurrentModification(payment).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("PAYMENT_ALREADY_RESOLVED");
    assertThat(handler.handleObjectConcurrentModification(payment).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(handler.handleObjectConcurrentModification(other).getBody())
        .extracting(BffErrorResponse::code)
        .isEqualTo("CONCURRENT_MODIFICATION");
  }
}
