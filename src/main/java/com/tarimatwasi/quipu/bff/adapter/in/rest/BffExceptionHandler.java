package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.application.port.in.AccountDisabledException;
import com.tarimatwasi.quipu.auth.application.port.in.AccountLockedException;
import com.tarimatwasi.quipu.auth.application.port.in.InvalidCredentialsException;
import com.tarimatwasi.quipu.auth.application.port.in.InvalidResetCodeException;
import com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException;
import com.tarimatwasi.quipu.auth.application.port.in.PasswordUnchangedException;
import com.tarimatwasi.quipu.auth.application.port.in.TemporaryPasswordExpiredException;
import com.tarimatwasi.quipu.auth.application.port.in.TooManyLoginAttemptsException;
import com.tarimatwasi.quipu.auth.application.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.contract.application.port.in.ContractFileUnavailableException;
import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractDatesException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractFileException;
import com.tarimatwasi.quipu.environment.application.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentInactiveException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.environment.application.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.expense.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseDescriptionException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseMonthException;
import com.tarimatwasi.quipu.guest.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentActionFailedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentLockedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestHasActiveContractException;
import com.tarimatwasi.quipu.guest.port.in.GuestInactiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotContractException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotTemporaryException;
import com.tarimatwasi.quipu.guest.port.in.InvalidDocumentActionException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestDocumentException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestStayException;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.StayOverlapException;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.NoActiveContractException;
import com.tarimatwasi.quipu.payment.port.in.NoActiveStayException;
import com.tarimatwasi.quipu.payment.port.in.NoStayYetException;
import com.tarimatwasi.quipu.payment.port.in.OnboardingRequiredException;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.port.in.PaymentPeriodNotPayableException;
import com.tarimatwasi.quipu.payment.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import java.time.YearMonth;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// Before Boot's ProblemDetails advice: the BFF answers {code, message, field?}, not problem+json.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.tarimatwasi.quipu.bff")
public class BffExceptionHandler {

  /** The 500 of the BFF: nothing internal in the body (TAR-187); GuestAreaFilter writes it too. */
  static final String INTERNAL_ERROR = "INTERNAL_ERROR";

  static final String INTERNAL_ERROR_MESSAGE =
      "No se pudo completar la acción; vuelve a intentarlo";

  private final IdMasker ids;

  public BffExceptionHandler(IdMasker ids) {
    this.ids = ids;
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidCredentials() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            new BffErrorResponse("AUTH_INVALID_CREDENTIALS", "Documento o contraseña incorrectos"));
  }

  /**
   * SEG-06: too many failed logins; the lock lifts by itself or with a password reset. TAR-131:
   * {@code Retry-After} (seconds left) is what the login counts down from, and {@code lockedUntil}
   * the time of day it shows.
   */
  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<BffAccountLockedResponse> handleAccountLocked(AccountLockedException e) {
    return ResponseEntity.status(HttpStatus.LOCKED)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
        .body(
            new BffAccountLockedResponse(
                "AUTH_ACCOUNT_LOCKED",
                "Cuenta bloqueada temporalmente por intentos fallidos. Inténtalo de nuevo más"
                    + " tarde o restablece tu contraseña",
                e.lockedUntil()));
  }

  /** SEC-01 (TAR-124): the same answer as the address limit of {@code RateLimitFilter}. */
  @ExceptionHandler(TooManyLoginAttemptsException.class)
  public ResponseEntity<BffErrorResponse> handleTooManyLoginAttempts(
      TooManyLoginAttemptsException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
        .body(
            new BffErrorResponse(
                "RATE_LIMITED",
                "Demasiados intentos, espera un momento antes de volver a intentar"));
  }

  /** Same body as the security entry point: the session is gone, whatever the reason. */
  @ExceptionHandler(NoActiveSessionException.class)
  public ResponseEntity<BffErrorResponse> handleNoActiveSession() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(new BffErrorResponse("AUTH_NO_SESSION", "Tu sesión no es válida o expiró"));
  }

  /** RN-32: the temporary password matched but expired; the administrator generates another. */
  @ExceptionHandler(TemporaryPasswordExpiredException.class)
  public ResponseEntity<BffErrorResponse> handleTemporaryPasswordExpired() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            new BffErrorResponse(
                "AUTH_TEMPORARY_PASSWORD_EXPIRED",
                "Tu contraseña temporal venció; pide a la administración un nuevo acceso"));
  }

  @ExceptionHandler(AccountDisabledException.class)
  public ResponseEntity<BffErrorResponse> handleAccountDisabled() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new BffErrorResponse("AUTH_ACCOUNT_DISABLED", "Cuenta deshabilitada"));
  }

  /** RNF-08: the message for a short password is the one SRS 17 gives for the field. */
  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<BffErrorResponse> handleWeakPassword(WeakPasswordException e) {
    String message =
        switch (e.reason()) {
          case TOO_SHORT -> "Mínimo 8 caracteres";
          case TOO_LONG -> "La contraseña es demasiado larga";
          case SINGLE_USE_CREDENTIAL ->
              "Elige una contraseña distinta del enlace o código recibido";
        };
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("AUTH_WEAK_PASSWORD", message, "newPassword"));
  }

  @ExceptionHandler(PasswordUnchangedException.class)
  public ResponseEntity<BffErrorResponse> handlePasswordUnchanged() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "AUTH_PASSWORD_UNCHANGED",
                "Elige una contraseña distinta de la temporal",
                "newPassword"));
  }

  /** QP-SPRMONO-BFF-01: stable English code, Spanish message, and the first invalid field. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidInput(MethodArgumentNotValidException e) {
    String field =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField())
            .sorted()
            .findFirst()
            .orElse(null);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", field));
  }

  /** RF-01: the code is the visible key of the environment; two cannot share it. */
  @ExceptionHandler(EnvironmentCodeTakenException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentCodeTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ENVIRONMENT_CODE_TAKEN", "Ya existe un ambiente con ese código", "code"));
  }

  /**
   * BE-SPR-DAT-13: a row was changed by someone else after it was read. It surfaces when the
   * transaction commits, so it is mapped here, once for every versioned entity.
   */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<BffErrorResponse> handleObjectConcurrentModification(
      ObjectOptimisticLockingFailureException e) {
    // A payment is resolved once: losing the race for it is "already resolved".
    String entity = e.getPersistentClassName();
    if (entity != null && entity.endsWith("PaymentJpaEntity")) {
      return handlePaymentAlreadyResolved();
    }
    return handleConcurrentModification();
  }

  /**
   * Two requests waited for each other's row locks and the database chose this one as the loser
   * (deadlock, 40P01) or a lock could not be taken: retrying is safe, nothing was committed. A wait
   * past lock_timeout (55P03, TAR-204) is one of them, translated by Spring or straight from JPA.
   */
  @ExceptionHandler({
    PessimisticLockingFailureException.class,
    jakarta.persistence.PessimisticLockException.class,
    jakarta.persistence.LockTimeoutException.class
  })
  public ResponseEntity<BffErrorResponse> handleLockConflict() {
    return handleConcurrentModification();
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<BffErrorResponse> handleConcurrentModification() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "CONCURRENT_MODIFICATION",
                "El registro cambió mientras lo editabas; vuelve a cargarlo e inténtalo de nuevo"));
  }

  @ExceptionHandler(EnvironmentNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("ENVIRONMENT_NOT_FOUND", "El ambiente no existe"));
  }

  @ExceptionHandler(EmptyEnvironmentUpdateException.class)
  public ResponseEntity<BffErrorResponse> handleEmptyEnvironmentUpdate() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Indica al menos un dato para cambiar"));
  }

  /** A code the schema let through that is empty, invisible or outside the allowed alphabet. */
  @ExceptionHandler(InvalidEnvironmentCodeException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidEnvironmentCode() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El código solo puede tener letras, números, espacios y . _ / -",
                "code"));
  }

  /** A body that is not JSON or has a value the type cannot read, such as an unknown enum. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<BffErrorResponse> handleUnreadableBody() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos"));
  }

  /** A field the closed schema of the body does not define. */
  @ExceptionHandler(UnknownFieldException.class)
  public ResponseEntity<BffErrorResponse> handleUnknownField(UnknownFieldException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.field()));
  }

  /**
   * RN-37: the guest does not exist, the document is not one of that guest, or another action on it
   * won. One body for all, so the answer does not tell which.
   */
  @ExceptionHandler(GuestDocumentNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleGuestDocumentNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("RESOURCE_NOT_FOUND", "El documento no existe"));
  }

  /** RN-37: the reason of a deletion or replacement breaks a rule; the field says which. */
  @ExceptionHandler(InvalidDocumentActionException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidDocumentAction(
      InvalidDocumentActionException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.field()));
  }

  /**
   * RN-37: the file of the document was deleted but its row and evidence were not; repeating the
   * action completes it. Nothing internal is in the body.
   */
  @ExceptionHandler(GuestDocumentActionFailedException.class)
  public ResponseEntity<BffErrorResponse> handleGuestDocumentActionFailed() {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new BffErrorResponse(INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
  }

  @ExceptionHandler(GuestNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleGuestNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("GUEST_NOT_FOUND", "El huésped no existe"));
  }

  /** RN-36: the document is corrected only while the guest has not activated its access. */
  @ExceptionHandler(GuestDocumentLockedException.class)
  public ResponseEntity<BffErrorResponse> handleGuestDocumentLocked() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_DOCUMENT_LOCKED",
                "El documento solo se corrige antes de que el huésped active su acceso",
                "documentNumber"));
  }

  /** RN-18: the document is the key of a person; two guests cannot share it. */
  @ExceptionHandler(GuestDocumentTakenException.class)
  public ResponseEntity<BffErrorResponse> handleGuestDocumentTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_DOCUMENT_TAKEN",
                "Ese documento ya pertenece a otro huésped o a una cuenta de administrador",
                "documentNumber"));
  }

  @ExceptionHandler(GuestEmailTakenException.class)
  public ResponseEntity<BffErrorResponse> handleGuestEmailTaken() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_EMAIL_TAKEN", "Ese correo ya pertenece a otro huésped", "contactEmail"));
  }

  /** A number the schema let through that does not fit its type (a DNI has 8 digits). */
  @ExceptionHandler(InvalidGuestDocumentException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidGuestDocument() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El número de documento no corresponde a su tipo",
                "documentNumber"));
  }

  /** Stay dates out of order or out of range, or an amount outside the allowed range. */
  @ExceptionHandler(InvalidGuestStayException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidGuestStay(InvalidGuestStayException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Los datos de la estadía no son válidos", e.field()));
  }

  @ExceptionHandler(EmptyGuestUpdateException.class)
  public ResponseEntity<BffErrorResponse> handleEmptyGuestUpdate() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Indica al menos un dato para cambiar"));
  }

  @ExceptionHandler(StayNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleStayNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("STAY_NOT_FOUND", "La estadía no existe"));
  }

  /** RN-35: only an active guest takes a new stay. */
  @ExceptionHandler(GuestNotActiveException.class)
  public ResponseEntity<BffGuestNotActiveResponse> handleGuestNotActive(GuestNotActiveException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffGuestNotActiveResponse(
                "GUEST_NOT_ACTIVE",
                "El huésped no está activo",
                ids.mask(IdKind.GUEST, e.guestId())));
  }

  /** RN-12, RN-18: the guest is inactive; {@code guestId} lets the UI reactivate it (CU-28). */
  @ExceptionHandler(GuestInactiveException.class)
  public ResponseEntity<BffGuestNotActiveResponse> handleGuestInactive(GuestInactiveException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffGuestNotActiveResponse(
                "GUEST_INACTIVE",
                "El huésped está inactivo; reactívalo primero",
                ids.mask(IdKind.GUEST, e.guestId())));
  }

  /** RN-13: only temporary guests have stays. */
  @ExceptionHandler(GuestNotTemporaryException.class)
  public ResponseEntity<BffErrorResponse> handleGuestNotTemporary() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_NOT_TEMPORARY", "Solo los huéspedes temporales tienen estadías"));
  }

  /** The dates of a stay cross another stay of the same guest. */
  @ExceptionHandler(StayOverlapException.class)
  public ResponseEntity<BffErrorResponse> handleStayOverlap() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "STAY_OVERLAP", "Las fechas se cruzan con otra estadía del huésped"));
  }

  /** RN-13: only guests with a contract take a contract. */
  @ExceptionHandler(GuestNotContractException.class)
  public ResponseEntity<BffErrorResponse> handleGuestNotContract() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "GUEST_NOT_CONTRACT", "Solo los huéspedes con contrato pueden tener un contrato"));
  }

  /** RN-25, RN-35: a guest that owes periods takes no new contract; the months, oldest first. */
  @ExceptionHandler(GuestHasPendingDebtException.class)
  public ResponseEntity<BffGuestHasPendingDebtResponse> handleGuestHasPendingDebt(
      GuestHasPendingDebtException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffGuestHasPendingDebtResponse(
                "GUEST_HAS_PENDING_DEBT",
                "El huésped tiene períodos pendientes de pago; primero regístralos",
                e.periods().stream().map(YearMonth::toString).toList()));
  }

  /** RN-12: a guest with a contract in force is not deactivated; the UI links the contract. */
  @ExceptionHandler(GuestHasActiveContractException.class)
  public ResponseEntity<BffGuestHasActiveContractResponse> handleGuestHasActiveContract(
      GuestHasActiveContractException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffGuestHasActiveContractResponse(
                "GUEST_HAS_ACTIVE_CONTRACT",
                "El huésped tiene un contrato vigente; primero finaliza el contrato",
                ids.mask(IdKind.CONTRACT, e.contractId())));
  }

  /** RF-01 (TAR-200): an inactive environment takes no new contract. */
  @ExceptionHandler(EnvironmentInactiveException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentInactive() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ENVIRONMENT_INACTIVE",
                "El ambiente está inactivo; reactívalo antes de registrar un contrato"));
  }

  /** RN-12, RN-20: a contract in force holds the environment. */
  @ExceptionHandler(EnvironmentOccupiedException.class)
  public ResponseEntity<BffErrorResponse> handleEnvironmentOccupied() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "ENVIRONMENT_OCCUPIED",
                "El ambiente tiene un contrato vigente; primero finaliza el contrato"));
  }

  /** An id of a payment that was never issued answers as a payment that does not exist. */
  @ExceptionHandler(PaymentNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("PAYMENT_NOT_FOUND", "El pago no existe"));
  }

  /** RN-05: a rejection says why. */
  @ExceptionHandler(PaymentReasonRequiredException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentReasonRequired() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "PAYMENT_REASON_REQUIRED", "Indica el motivo del rechazo", "reason"));
  }

  /** A field of the payment outside its range, or the choice of period or stay. */
  @ExceptionHandler(InvalidPaymentException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidPayment(InvalidPaymentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Los datos del pago no son válidos", e.field()));
  }

  /** RF-15: a JPG, a PNG or a PDF by content, up to 5 MB. */
  @ExceptionHandler(InvalidVoucherFileException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidVoucherFile() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VOUCHER_INVALID_FILE",
                "El comprobante debe ser una imagen JPG o PNG, o un PDF, de hasta 5 MB",
                "file"));
  }

  /** The payment is no longer pending: another process resolved it first. */
  @ExceptionHandler(PaymentAlreadyResolvedException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentAlreadyResolved() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "PAYMENT_ALREADY_RESOLVED", "El pago ya fue resuelto; vuelve a cargarlo"));
  }

  /**
   * TAR-204, TAR-207: the contract no longer charges the period of the voucher (terminated or
   * expired first), so it cannot be approved; it stays pending and can only be rejected. Its own
   * code since the contract v0.9.2 ({@code PaymentPeriodNotPayableError}).
   */
  @ExceptionHandler(PaymentPeriodNotPayableException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentPeriodNotPayable() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "PAYMENT_PERIOD_NOT_PAYABLE",
                "El contrato ya no cobra el período de este pago; solo puedes rechazarlo indicando"
                    + " el motivo"));
  }

  /** The payment that blocked a period was gone when it was looked for: try again. */
  @ExceptionHandler(PaymentConcurrentChangeException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentConcurrentChange() {
    return handleConcurrentModification();
  }

  /** RN-02: an approved payment covers the period or the stay. */
  @ExceptionHandler(PaymentAlreadyExistsForPeriodException.class)
  public ResponseEntity<BffErrorResponse> handlePaymentAlreadyExistsForPeriod() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new BffErrorResponse(
                "PAYMENT_ALREADY_EXISTS_FOR_PERIOD",
                "El período o la estadía ya tiene un pago aprobado"));
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

  /** RN-09: a route of the other type of guest, answered like any route the role cannot use. */
  @ExceptionHandler(OtherGuestTypeException.class)
  public ResponseEntity<BffErrorResponse> handleOtherGuestType() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new BffErrorResponse("AUTH_FORBIDDEN", "No tienes permiso para esta acción"));
  }

  /** RN-33: the guest finishes the onboarding before anything else. */
  @ExceptionHandler(OnboardingRequiredException.class)
  public ResponseEntity<BffErrorResponse> handleOnboardingRequired() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            new BffErrorResponse(
                "AUTH_ONBOARDING_REQUIRED", "Completa tu registro para continuar"));
  }

  @ExceptionHandler(ContractNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleContractNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("CONTRACT_NOT_FOUND", "El contrato no existe"));
  }

  /** A field of the contract outside its range. */
  @ExceptionHandler(InvalidContractException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidContract(InvalidContractException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Los datos del contrato no son válidos", e.field()));
  }

  /** RF-03: the contract ends after it starts. */
  @ExceptionHandler(InvalidContractDatesException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidContractDates() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "CONTRACT_INVALID_DATES",
                "La fecha de fin debe ser posterior a la de inicio",
                "endDate"));
  }

  /** RF-15: a PDF by content, up to 10 MB (the multipart limit has its own advice). */
  @ExceptionHandler(InvalidContractFileException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidContractFile() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "CONTRACT_INVALID_FILE", "El contrato debe ser un PDF de hasta 10 MB", "file"));
  }

  /** RN-33, RN-37: a step out of order, an onboarding already complete or 5 documents already. */
  @ExceptionHandler(OnboardingConflictException.class)
  public ResponseEntity<BffErrorResponse> handleOnboardingConflict(OnboardingConflictException e) {
    BffErrorResponse body =
        switch (e.reason()) {
          case STEP_OUT_OF_ORDER ->
              new BffErrorResponse(
                  "ONBOARDING_STEP_OUT_OF_ORDER", "Completa primero el paso anterior del registro");
          case ALREADY_COMPLETED ->
              new BffErrorResponse(
                  "ONBOARDING_ALREADY_COMPLETED",
                  "Tu registro ya está completo; edita tus datos desde tu perfil");
          case DOCUMENT_LIMIT_REACHED ->
              new BffErrorResponse(
                  "DOCUMENT_LIMIT_REACHED", "Ya subiste los 5 documentos que se permiten");
        };
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /** RF-15, RN-14: a file that is not a JPG, PNG or PDF of up to 5 MB, or a consent not given. */
  @ExceptionHandler(InvalidOnboardingInputException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidOnboardingInput(
      InvalidOnboardingInputException e) {
    BffErrorResponse body =
        switch (e.reason()) {
          case DOCUMENT_INVALID_FILE -> invalidDocumentFile();
          case CONSENT_NOT_ACCEPTED ->
              new BffErrorResponse(
                  "CONSENT_NOT_ACCEPTED",
                  "Debes aceptar el consentimiento para completar tu registro",
                  "accepted");
          case CONSENT_VERSION ->
              new BffErrorResponse(
                  "VALIDATION_ERROR",
                  "El texto del consentimiento cambió; vuelve a cargarlo",
                  "consentVersion");
        };
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** The same answer for a document refused by its content and by the multipart limit. */
  static BffErrorResponse invalidDocumentFile() {
    return new BffErrorResponse(
        "DOCUMENT_INVALID_FILE",
        "El documento debe ser una imagen JPG o PNG, o un PDF, de hasta 5 MB",
        "file");
  }

  /** The bucket is not configured or did not answer; nothing was stored. */
  @ExceptionHandler({
    ContractFileUnavailableException.class,
    PaymentFileUnavailableException.class,
    GuestDocumentUnavailableException.class
  })
  public ResponseEntity<BffErrorResponse> handleFileStorage() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            new BffErrorResponse(
                "FILE_STORAGE_UNAVAILABLE",
                "No se pudo acceder al archivo; inténtalo de nuevo más tarde"));
  }

  @ExceptionHandler(ExpenseNotFoundException.class)
  public ResponseEntity<BffErrorResponse> handleExpenseNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new BffErrorResponse("EXPENSE_NOT_FOUND", "El egreso no existe"));
  }

  /** RF-07: the amount is positive and has at most two decimals. */
  @ExceptionHandler(InvalidExpenseException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidExpense() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "El monto debe ser mayor que 0 y tener como máximo 2 decimales",
                "amount"));
  }

  /** A month of the pattern that the base does not keep as it is, such as year 0000. */
  @ExceptionHandler(InvalidExpenseMonthException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidExpenseMonth() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", "month"));
  }

  /** The description is longer than 500 characters. */
  @ExceptionHandler(InvalidExpenseDescriptionException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidExpenseDescription() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR",
                "La descripción admite como máximo 500 caracteres",
                "description"));
  }

  /** A query parameter that is there but not in the documented shape. */
  @ExceptionHandler(InvalidQueryParameterException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidQueryParameter(
      InvalidQueryParameterException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.parameter()));
  }

  /** A page cursor that this service did not issue, or that was altered (BE-SPR-WEB-08). */
  @ExceptionHandler(InvalidCursorException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidCursor() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", "after"));
  }

  /** A required query parameter that is not there. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<BffErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "VALIDATION_ERROR", "Datos de entrada inválidos", e.getParameterName()));
  }

  /**
   * A well-formed masked id that was never issued for that kind answers as the resource that does
   * not exist, with the same body, so that a client cannot tell a forged id from a missing one. A
   * value that is not a UUID at all is still a 400.
   */
  @ExceptionHandler(UnknownMaskedIdException.class)
  public ResponseEntity<BffErrorResponse> handleUnknownMaskedId(UnknownMaskedIdException e) {
    return switch (e.kind()) {
      case ENVIRONMENT -> handleEnvironmentNotFound();
      case EXPENSE -> handleExpenseNotFound();
      case GUEST -> handleGuestNotFound();
      case STAY -> handleStayNotFound();
      case CONTRACT -> handleContractNotFound();
      case PAYMENT -> handlePaymentNotFound();
      // The session reads the user id itself and refuses the token: no BFF path carries one.
      case USER -> throw new IllegalStateException("A user id is never part of a BFF path");
      case GUEST_DOCUMENT -> handleGuestDocumentNotFound();
      case FINE -> BffFineExceptionHandler.fineNotFound();
      // An action is only listed, never addressed by its id.
      case GUEST_DOCUMENT_ACTION ->
          throw new IllegalStateException("A document action id is never part of a BFF path");
    };
  }

  /** A path or query value of the wrong shape, such as an id that is not a UUID. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<BffErrorResponse> handleBadParameter(
      MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new BffErrorResponse("VALIDATION_ERROR", "Datos de entrada inválidos", e.getName()));
  }

  /** One answer for an unknown, expired or used code and for a disabled account (RF-16). */
  @ExceptionHandler(InvalidResetCodeException.class)
  public ResponseEntity<BffErrorResponse> handleInvalidResetCode() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new BffErrorResponse(
                "AUTH_INVALID_OR_EXPIRED_CODE", "Enlace inválido o expirado, solicita uno nuevo"));
  }
}
