package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveContractException;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveStayException;
import com.tarimatwasi.quipu.payment.application.port.in.NoStayYetException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotCorrectableException;
import com.tarimatwasi.quipu.payment.application.port.in.VoucherAlreadyExistsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RF-04, RF-05, RN-15 (TAR-239): the errors of sending or correcting a payment, apart from {@link
 * BffExceptionHandler} so that it stays within the size of a file (BE-SPR-COD-09).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffVoucherExceptionHandler {

  /** RF-15: a JPG, a PNG or a PDF by content, up to 5 MB. */
  @ExceptionHandler(InvalidVoucherFileException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidVoucherFile(InvalidVoucherFileException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VOUCHER_INVALID_FILE",
                "El comprobante debe ser una imagen JPG o PNG, o un PDF, de hasta 5 MB",
                e.field()));
  }

  /**
   * TAR-239: the guest corrects only a rejected payment, the administrator a pending or rejected
   * one.
   */
  @ExceptionHandler(PaymentNotCorrectableException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentNotCorrectable() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "PAYMENT_NOT_CORRECTABLE",
                "Este pago ya no se puede corregir: está en revisión o ya se aprobó"));
  }

  /** RN-02: an approved payment covers the period or the stay. */
  @ExceptionHandler(PaymentAlreadyExistsForPeriodException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentAlreadyExistsForPeriod() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "PAYMENT_ALREADY_EXISTS_FOR_PERIOD",
                "Ya existe un pago aprobado para este período"));
  }

  /** RN-15: a voucher is still waiting for its review. */
  @ExceptionHandler(VoucherAlreadyExistsException.class)
  public ResponseEntity<BffErrorResponse> handleVoucherAlreadyExists() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "VOUCHER_ALREADY_EXISTS",
                "Ya hay un comprobante pendiente de revisión; resuélvelo primero"));
  }

  /** RN-27: a guest uploads a voucher only while its contract is in force. */
  @ExceptionHandler(NoActiveContractException.class)
  public ResponseEntity<BffErrorResponse> handleNoActiveContract() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new BffErrorResponse(
                "VOUCHER_NO_ACTIVE_CONTRACT",
                "No tienes un contrato vigente; consulta con la administración"));
  }

  /** CU-22: a temporary guest pays only a stay in course or to come. */
  @ExceptionHandler(NoActiveStayException.class)
  public ResponseEntity<BffErrorResponse> handleNoActiveStay() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new BffErrorResponse(
                "VOUCHER_NO_ACTIVE_STAY",
                "No tienes una estadía vigente ni próxima; consulta con la administración"));
  }

  /** CU-21: a temporary guest with no stay has no payment status. */
  @ExceptionHandler(NoStayYetException.class)
  public ResponseEntity<BffErrorResponse> handleNoStayYet() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "Aún no tienes una estadía registrada"));
  }
}
