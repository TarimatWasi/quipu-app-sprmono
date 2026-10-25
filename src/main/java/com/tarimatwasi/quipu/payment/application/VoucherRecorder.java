package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.StoredPayment;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What the administrator and the guest share when they register a voucher: the checks of the
 * declared fields, the conflict of a live payment, and storing the file so that it never outlives a
 * payment that was not saved.
 */
final class VoucherRecorder {

  private static final Logger LOGGER = LoggerFactory.getLogger(VoucherRecorder.class);

  /** VARCHAR(50) of payment_method. */
  private static final int MAX_METHOD_LENGTH = 50;

  /** The name of a voucher whose browser name is unusable. */
  static final String DEFAULT_NAME = "comprobante";

  private final PaymentFilePort files;

  VoucherRecorder(PaymentFilePort files) {
    this.files = files;
  }

  /** The amount and the optional method a voucher declares (RF-05, RN-28). */
  static void checkAmountAndMethod(BigDecimal amount, @Nullable String method) {
    if (!Payment.validAmount(amount)) {
      throw new InvalidPaymentException("amount");
    }
    if (method != null
        && (PaymentText.characters(method.strip()) > MAX_METHOD_LENGTH
            || PaymentText.hasUnsafeCharacter(method))) {
      throw new InvalidPaymentException("paymentMethod");
    }
  }

  /** An approved payment is a conflict of its own (RN-02); a pending one asks to be resolved. */
  static void rejectLive(Optional<Payment> live) {
    if (live.isPresent()) {
      throw conflictOf(live.get().status());
    }
  }

  /** The conflict for the live payment that is there; if it is gone, a neutral one (try again). */
  static RuntimeException conflictOf(@Nullable PaymentStatus live) {
    if (live == null) {
      return new PaymentConcurrentChangeException();
    }
    return live == PaymentStatus.PENDING
        ? new VoucherAlreadyExistsException()
        : new PaymentAlreadyExistsForPeriodException();
  }

  static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  /**
   * The object goes to the bucket first, then the row is inserted with its key; if the row fails,
   * the object is removed. A live payment that got there first is the conflict it is, never a 500.
   */
  StoredPayment store(byte[] content, VoucherType type, Function<String, StoredPayment> insert) {
    String key = storeFile(content, type);
    boolean removedIfNotCommitted = removeUnlessCommitted(key);
    StoredPayment saved = null;
    try {
      saved = insert.apply(key);
    } catch (LivePaymentExistsException raced) {
      // Another request got the period or the stay between the check and the insert.
      throw conflictOf(raced.liveStatus());
    } finally {
      if (saved == null && !removedIfNotCommitted) {
        discard(key);
      }
    }
    return saved;
  }

  /**
   * Inside a transaction the object is removed whenever the transaction does not commit: the insert
   * failed, or something after it did, or the commit itself failed. Returns true if it will be;
   * with no transaction the caller removes it.
   */
  private boolean removeUnlessCommitted(String key) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              discard(key);
            }
          }
        });
    return true;
  }

  private String storeFile(byte[] content, VoucherType type) {
    try {
      return files.store(content, type);
    } catch (PaymentFileStorageException e) {
      throw new PaymentFileUnavailableException(e);
    }
  }

  /**
   * A failure to clean up must not hide the failure that made the cleanup necessary. It is logged
   * without the storage key (TAR-205).
   */
  private void discard(String key) {
    try {
      files.delete(key);
    } catch (PaymentFileStorageException cleanupFailed) {
      // TAR-205: the class names only; the message and trace of the SDK name the R2 host
      // (account and bucket) and may name the key.
      Throwable cause = cleanupFailed.getCause();
      LOGGER.error(
          "A voucher stays in the bucket without a payment ({}, cause {})",
          cleanupFailed.getClass().getName(),
          cause == null ? "-" : cause.getClass().getName());
    }
  }

  /** A payment without its voucher URL. */
  static PaymentView view(StoredPayment stored) {
    Payment payment = stored.payment();
    return new PaymentView(
        payment.id(),
        payment.guestId(),
        payment.contractId(),
        payment.stayId(),
        payment.period(),
        payment.amount(),
        payment.paymentMethod(),
        PaymentState.valueOf(payment.status().name()),
        payment.rejectionReason(),
        payment.reviewedAt(),
        stored.createdAt());
  }
}
