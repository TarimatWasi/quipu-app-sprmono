package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofEdit;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofFileView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofSource;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ReviewView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotCorrectableException;
import com.tarimatwasi.quipu.payment.application.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewProof;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.domain.CorrectionNotAllowedException;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.PaymentReview;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.ProofKind;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.FileNames;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What the administrator and the guest share about the proofs of a payment (RN-22, TAR-239): their
 * checks, storing their files so that none outlives a payment that was not saved, removing the ones
 * a correction replaced once it commits, and how a payment is shown.
 */
final class ProofRecorder {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProofRecorder.class);

  /** VARCHAR(50) of payment_proofs.method. */
  private static final int MAX_METHOD_LENGTH = 50;

  /** The name of a voucher whose browser name is unusable. */
  static final String DEFAULT_NAME = "comprobante";

  private final PaymentFilePort files;
  private final PaymentRepositoryPort payments;

  ProofRecorder(PaymentFilePort files, PaymentRepositoryPort payments) {
    this.files = files;
    this.payments = payments;
  }

  // --- the checks, before any lookup ---

  /** The proofs of a new payment: 1 to 5, each with its amount, method and file (RN-22). */
  static void checkUploads(List<ProofUpload> proofs) {
    checkCount(proofs.size());
    for (int i = 0; i < proofs.size(); i++) {
      ProofUpload proof = proofs.get(i);
      checkProof(i, proof.kind(), proof.amount(), proof.method(), proof.file(), true);
    }
  }

  /** The proofs of a correction: a new voucher needs its file, a kept one may keep its own. */
  static void checkEdits(List<ProofEdit> proofs) {
    checkCount(proofs.size());
    Set<Long> seen = new HashSet<>();
    for (int i = 0; i < proofs.size(); i++) {
      ProofEdit proof = proofs.get(i);
      Long id = proof.id();
      if (id != null && !seen.add(id)) {
        throw new InvalidPaymentException("proofs[" + i + "].id");
      }
      checkProof(i, proof.kind(), proof.amount(), proof.method(), proof.file(), id == null);
    }
  }

  private static void checkCount(int count) {
    if (count == 0 || count > ManagePaymentsUseCase.MAX_PROOFS) {
      throw new InvalidPaymentException("proofs");
    }
  }

  private static void checkProof(
      int index,
      ProofType kind,
      BigDecimal amount,
      @Nullable String method,
      @Nullable VoucherUpload file,
      boolean voucherNeedsFile) {
    String field = "proofs[" + index + "].";
    if (!Payment.validAmount(amount)) {
      throw new InvalidPaymentException(field + "amount");
    }
    if (method != null
        && (PaymentText.characters(method.strip()) > MAX_METHOD_LENGTH
            || PaymentText.hasUnsafeCharacter(method))) {
      throw new InvalidPaymentException(field + "method");
    }
    boolean fileMismatch = kind == ProofType.CASH ? file != null : voucherNeedsFile && file == null;
    if (fileMismatch) {
      throw new InvalidPaymentException(field + "fileIndex");
    }
  }

  /**
   * The type of every file, by its content (RF-15), checked before any row is locked or any object
   * stored; the error names the proof whose file it is.
   */
  static Map<Integer, VoucherType> typesOfUploads(List<ProofUpload> proofs) {
    List<@Nullable VoucherUpload> files = new ArrayList<>();
    proofs.forEach(proof -> files.add(proof.file()));
    return typesOf(files);
  }

  /** Like {@link #typesOfUploads} for the proofs of a correction. */
  static Map<Integer, VoucherType> typesOfEdits(List<ProofEdit> proofs) {
    List<@Nullable VoucherUpload> files = new ArrayList<>();
    proofs.forEach(proof -> files.add(proof.file()));
    return typesOf(files);
  }

  private static Map<Integer, VoucherType> typesOf(List<@Nullable VoucherUpload> filesByProof) {
    Map<Integer, VoucherType> types = new HashMap<>();
    for (int i = 0; i < filesByProof.size(); i++) {
      VoucherUpload file = filesByProof.get(i);
      if (file != null) {
        int index = i;
        types.put(
            i,
            VoucherFile.typeOf(file.content())
                .orElseThrow(() -> new InvalidVoucherFileException("proofs[" + index + "].file")));
      }
    }
    return types;
  }

  static BigDecimal declaredSum(List<ProofUpload> proofs) {
    BigDecimal sum = BigDecimal.ZERO;
    for (ProofUpload proof : proofs) {
      sum = sum.add(proof.amount());
    }
    return sum;
  }

  /**
   * An approved payment is a conflict of its own (RN-02); a pending or rejected one asks for it.
   */
  static void rejectLive(@Nullable Payment live) {
    if (live != null) {
      throw conflictOf(live.status());
    }
  }

  /** The conflict for the payment that is there; if it is gone, a neutral one (try again). */
  static RuntimeException conflictOf(@Nullable PaymentStatus live) {
    if (live == null) {
      return new PaymentConcurrentChangeException();
    }
    return live == PaymentStatus.APPROVED
        ? new PaymentAlreadyExistsForPeriodException()
        : new VoucherAlreadyExistsException();
  }

  static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  // --- storing a new payment ---

  /**
   * The files go to the bucket first, then the payment is inserted with their keys; if the
   * transaction does not commit, the files are removed. A payment that got there first is the
   * conflict it is, never a 500. The internal id of the guest only names whose voucher a failed
   * cleanup leaves behind (TAR-216).
   *
   * @param toPayment builds the payment from the proofs, each with its stored file
   */
  StoredPayment insert(
      Long guestId,
      List<ProofUpload> proofs,
      Map<Integer, VoucherType> types,
      ProofAuthor author,
      @Nullable LocalDate receivedOn,
      boolean verifiedAtOnce,
      Function<List<NewProof>, NewPayment> toPayment) {
    List<String> stored = new ArrayList<>();
    boolean removedIfNotCommitted = removeUnlessCommitted(guestId, stored);
    StoredPayment saved = null;
    try {
      List<NewProof> added = new ArrayList<>();
      for (int i = 0; i < proofs.size(); i++) {
        ProofUpload proof = proofs.get(i);
        ProofFile file = store(proof.file(), types.get(i), stored);
        added.add(
            new NewProof(
                kindOf(proof.kind()),
                proof.amount().setScale(2),
                verifiedAtOnce ? proof.amount().setScale(2) : null,
                blankToNull(proof.method()),
                file,
                author,
                receivedOn));
      }
      saved = payments.insert(toPayment.apply(added));
    } catch (LivePaymentExistsException raced) {
      // Another request got the period or the stay between the check and the insert.
      throw conflictOf(raced.liveStatus());
    } finally {
      if (saved == null && !removedIfNotCommitted) {
        stored.forEach(key -> discard(guestId, key));
      }
    }
    return saved;
  }

  // --- correcting the proofs of a payment that exists (TAR-239) ---

  /**
   * Replaces the proofs of a locked payment: the listed ones with an id are kept with their new
   * amount, method or file, the ones without id are added by {@code author}, and the rest are
   * removed. The verified amounts are cleared, since the payment is not approved. The files that a
   * correction leaves behind are removed once it commits.
   *
   * @param resubmitted whether the payment is sent again (the guest's correction)
   * @throws PaymentNotCorrectableException if the payment cannot be corrected in its status
   * @throws InvalidPaymentException if an id is not a proof of this payment
   */
  StoredPayment correct(
      StoredPayment locked,
      List<ProofEdit> edits,
      Map<Integer, VoucherType> types,
      ProofAuthor author,
      boolean resubmitted,
      Instant now) {
    Payment payment = locked.payment();
    BigDecimal declared = BigDecimal.ZERO;
    for (ProofEdit edit : edits) {
      declared = declared.add(edit.amount().setScale(2));
    }
    Payment corrected;
    try {
      corrected = payment.corrected(declared, resubmitted, now);
    } catch (CorrectionNotAllowedException notNow) {
      throw new PaymentNotCorrectableException();
    }
    Map<Long, PaymentProof> current = new HashMap<>();
    locked.proofs().forEach(proof -> current.put(proof.id(), proof));
    checkAgainst(edits, current);
    List<String> stored = new ArrayList<>();
    removeUnlessCommitted(payment.guestId(), stored);
    List<String> leftBehind = new ArrayList<>();
    Set<Long> keptIds = new HashSet<>();
    for (int i = 0; i < edits.size(); i++) {
      ProofEdit edit = edits.get(i);
      ProofFile newFile = store(edit.file(), types.get(i), stored);
      Long id = edit.id();
      if (id == null) {
        add(payment.id(), edit, newFile, author);
        continue;
      }
      keptIds.add(id);
      update(Objects.requireNonNull(current.get(id)), edit, newFile, leftBehind);
    }
    List<Long> removed = new ArrayList<>();
    for (PaymentProof proof : locked.proofs()) {
      if (!keptIds.contains(proof.id())) {
        removed.add(proof.id());
        ProofFile file = proof.file();
        if (file != null) {
          leftBehind.add(file.key());
        }
      }
    }
    payments.removeProofs(payment.id(), removed);
    StoredPayment saved = payments.save(corrected);
    removeOnceCommitted(payment.guestId(), leftBehind);
    return saved;
  }

  /** A proof the correction adds. */
  private void add(Long paymentId, ProofEdit edit, @Nullable ProofFile file, ProofAuthor author) {
    payments.addProof(
        paymentId,
        new NewProof(
            kindOf(edit.kind()),
            edit.amount().setScale(2),
            null,
            blankToNull(edit.method()),
            file,
            author,
            null));
  }

  /** Every id names a proof of the payment, and cash becoming a voucher brings its file. */
  private static void checkAgainst(List<ProofEdit> edits, Map<Long, PaymentProof> current) {
    for (int i = 0; i < edits.size(); i++) {
      Long id = edits.get(i).id();
      if (id != null && !current.containsKey(id)) {
        throw new InvalidPaymentException("proofs[" + i + "].id");
      }
      PaymentProof kept = id == null ? null : current.get(id);
      if (kept != null
          && edits.get(i).kind() == ProofType.VOUCHER
          && edits.get(i).file() == null
          && kept.file() == null) {
        throw new InvalidPaymentException("proofs[" + i + "].fileIndex");
      }
    }
  }

  /** A kept proof with the edit applied; a file it no longer uses is left behind to remove. */
  private void update(
      PaymentProof kept, ProofEdit edit, @Nullable ProofFile newFile, List<String> leftBehind) {
    ProofFile oldFile = kept.file();
    ProofFile file = edit.kind() == ProofType.CASH ? null : newFile != null ? newFile : oldFile;
    if (oldFile != null && !oldFile.equals(file)) {
      leftBehind.add(oldFile.key());
    }
    payments.updateProof(
        new PaymentProof(
            kept.id(),
            kindOf(edit.kind()),
            edit.amount().setScale(2),
            null,
            blankToNull(edit.method()),
            file,
            kept.uploadedBy(),
            kept.receivedOn(),
            kept.addedAt()));
  }

  private static ProofKind kindOf(ProofType type) {
    return ProofKind.valueOf(type.name());
  }

  private @Nullable ProofFile store(
      @Nullable VoucherUpload upload, @Nullable VoucherType type, List<String> stored) {
    if (upload == null || type == null) {
      return null;
    }
    String key;
    try {
      key = files.store(upload.content(), type);
    } catch (PaymentFileStorageException e) {
      throw new PaymentFileUnavailableException(e);
    }
    stored.add(key);
    return new ProofFile(
        key,
        FileNames.sanitized(upload.originalName(), DEFAULT_NAME),
        type,
        upload.content().length);
  }

  /**
   * Inside a transaction the objects stored so far are removed whenever it does not commit: an
   * insert failed, or something after it did, or the commit itself failed. Returns true if they
   * will be; with no transaction the caller removes them.
   */
  private boolean removeUnlessCommitted(Long guestId, List<String> stored) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              stored.forEach(key -> discard(guestId, key));
            }
          }
        });
    return true;
  }

  /** The files a correction replaced or removed leave the bucket only once it commits. */
  private void removeOnceCommitted(Long guestId, List<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      keys.forEach(key -> discard(guestId, key));
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            keys.forEach(key -> discard(guestId, key));
          }
        });
  }

  /**
   * A failure to clean up must not hide the failure that made the cleanup necessary. It is logged
   * without the storage key (TAR-205), with the internal id of the guest so that the orphan can be
   * traced (TAR-216).
   */
  private void discard(Long guestId, String key) {
    try {
      files.delete(key);
    } catch (PaymentFileStorageException cleanupFailed) {
      // TAR-205: the class names only; the message and trace of the SDK name the R2 host
      // (account and bucket) and may name the key.
      Throwable cause = cleanupFailed.getCause();
      LOGGER.error(
          "A voucher of guest {} stays in the bucket without a payment ({}, cause {})",
          guestId,
          cleanupFailed.getClass().getName(),
          cause == null ? "-" : cause.getClass().getName());
    }
  }

  // --- how a payment is shown ---

  /** A payment without its proofs' URLs. */
  static PaymentView view(StoredPayment stored) {
    Payment payment = stored.payment();
    List<PaymentProof> proofs = stored.proofs();
    int vouchers = (int) proofs.stream().filter(p -> p.kind() == ProofKind.VOUCHER).count();
    return new PaymentView(
        payment.id(),
        payment.guestId(),
        payment.contractId(),
        payment.stayId(),
        payment.period(),
        payment.amount(),
        payment.verifiedAmount(),
        proofs.isEmpty() ? null : proofs.get(0).method(),
        state(payment.status()),
        payment.rejectionReason(),
        proofs.size(),
        vouchers,
        vouchers < proofs.size(),
        payment.reviewedAt(),
        payment.submittedAt(),
        stored.createdAt());
  }

  static PaymentState state(PaymentStatus status) {
    return PaymentState.valueOf(status.shown().name());
  }

  /** A proof; the URL only in the administrator's detail. */
  static ProofView proofView(PaymentProof proof, @Nullable String fileUrl) {
    ProofFile file = proof.file();
    return new ProofView(
        proof.id(),
        ProofType.valueOf(proof.kind().name()),
        proof.method(),
        proof.declaredAmount(),
        proof.verifiedAmount(),
        ProofSource.valueOf(proof.uploadedBy().name()),
        proof.addedAt(),
        proof.receivedOn(),
        file == null
            ? null
            : new ProofFileView(file.originalName(), file.type().contentType(), file.sizeBytes()),
        fileUrl);
  }

  static ReviewView reviewView(PaymentReview review) {
    return new ReviewView(Decision.valueOf(review.decision().name()), review.reason(), review.at());
  }
}
