package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.InvalidRejectionReasonException;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherFile;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.DirectDraft;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.shared.file.FileNames;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class PaymentService implements ManagePaymentsUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentService.class);

  /** VARCHAR(50) of payment_method. */
  private static final int MAX_METHOD_LENGTH = 50;

  private static final String DEFAULT_NAME = "comprobante";

  private final PaymentRepositoryPort payments;
  private final PaymentFilePort files;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentEnvironmentPort environments;
  private final PaymentStayPort stays;
  private final Clock clock;

  PaymentService(
      PaymentRepositoryPort payments,
      PaymentFilePort files,
      PaymentGuestPort guests,
      PaymentContractPort contracts,
      PaymentEnvironmentPort environments,
      PaymentStayPort stays,
      Clock clock) {
    this.payments = payments;
    this.files = files;
    this.guests = guests;
    this.contracts = contracts;
    this.environments = environments;
    this.stays = stays;
    this.clock = clock;
  }

  // --- the queue and the detail ---

  @Override
  @Transactional(readOnly = true)
  public CursorPage<QueueItemView> list(@Nullable PaymentState status, PageQuery page) {
    CursorPage<StoredPayment> found =
        payments.findQueue(status == null ? null : PaymentStatus.valueOf(status.name()), page);
    List<Payment> rows = found.content().stream().map(StoredPayment::payment).toList();
    Map<Long, GuestProfile> profiles =
        guests.profilesOf(rows.stream().map(Payment::guestId).collect(Collectors.toSet()));
    Map<Long, Long> environmentOfContract =
        contracts.environmentsOf(
            rows.stream()
                .map(Payment::contractId)
                .filter(id -> id != null)
                .collect(Collectors.toSet()));
    Map<Long, String> codes = environments.codesOf(Set.copyOf(environmentOfContract.values()));
    List<QueueItemView> items = new ArrayList<>();
    for (StoredPayment stored : found.content()) {
      GuestProfile guest = profiles.get(stored.payment().guestId());
      if (guest == null) {
        // The table links every payment to a guest; if one is gone anyway, the queue keeps working.
        LOGGER.warn("Payment {} has no guest and is left out of the queue", stored.payment().id());
      } else {
        items.add(queueItem(stored, guest, environmentOfContract, codes));
      }
    }
    return new CursorPage<>(items, found.nextCursor());
  }

  private static QueueItemView queueItem(
      StoredPayment stored,
      GuestProfile guest,
      Map<Long, Long> environmentOfContract,
      Map<Long, String> codes) {
    Payment payment = stored.payment();
    Long contractId = payment.contractId();
    Long environmentId = contractId == null ? null : environmentOfContract.get(contractId);
    return new QueueItemView(
        payment.id(),
        payment.guestId(),
        guest.name(),
        guest.documentNumber(),
        guest.contractType() ? GuestKind.CONTRACT : GuestKind.TEMPORARY,
        environmentId == null ? null : codes.get(environmentId),
        payment.period(),
        PaymentState.valueOf(payment.status().name()),
        stored.createdAt());
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentDetailView get(Long id) {
    StoredPayment stored = payments.findById(id).orElseThrow(PaymentNotFoundException::new);
    return new PaymentDetailView(view(stored), signedUrl(stored));
  }

  private String signedUrl(StoredPayment stored) {
    try {
      return files.signedUrl(stored.voucherKey(), stored.voucherType());
    } catch (PaymentFileStorageException e) {
      throw new PaymentFileUnavailableException(e);
    }
  }

  // --- the review ---

  @Override
  @Transactional
  public PaymentView resolve(Long id, Resolution resolution) {
    // The row stays locked until the commit: two reviewers of one voucher are decided one by one.
    StoredPayment stored =
        payments.findByIdForUpdate(id).orElseThrow(PaymentNotFoundException::new);
    Payment payment = stored.payment();
    if (!payment.pending()) {
      throw new PaymentAlreadyResolvedException();
    }
    Instant now = clock.instant();
    Payment resolved =
        resolution.decision() == Decision.APPROVED
            ? payment.approve(resolution.reviewerId(), now)
            : reject(payment, resolution, now);
    return view(payments.save(resolved));
  }

  /**
   * A rejection needs a reason of 1 to 500 characters (RN-05) without control characters (a tab or
   * a line break alone is one of them, not a blank). Without a reason, or with a blank one, it is a
   * missing reason; one that is too long is a field out of range.
   */
  private static Payment reject(Payment payment, Resolution resolution, Instant now) {
    String reason = resolution.reason();
    if (reason != null && PaymentText.hasUnsafeCharacter(reason)) {
      throw new InvalidPaymentException("reason");
    }
    try {
      return payment.reject(resolution.reviewerId(), reason, now);
    } catch (InvalidRejectionReasonException invalid) {
      throw reason != null && PaymentText.characters(reason.strip()) > Payment.MAX_REASON_LENGTH
          ? new InvalidPaymentException("reason")
          : new PaymentReasonRequiredException();
    }
  }

  // --- the payment registered by hand ---

  @Override
  @Transactional
  public PaymentView registerDirect(DirectPayment direct) {
    validate(direct);
    GuestProfile guest = guests.profileOf(direct.guestId());
    Long stayId = direct.stayId();
    if (stayId != null) {
      stays.stayOf(direct.guestId(), stayId);
    }
    if (!guest.active()) {
      throw new GuestNotActiveException(direct.guestId());
    }
    Target target = stayId != null ? stayTarget(guest, stayId) : periodTarget(guest, direct);
    VoucherType type =
        VoucherFile.typeOf(direct.voucher().content())
            .orElseThrow(InvalidVoucherFileException::new);
    rejectLivePayment(target);
    return store(direct, target, type);
  }

  /** Both amount and method are checked before any lookup; so is the choice of period or stay. */
  private static void validate(DirectPayment direct) {
    if ((direct.period() == null) == (direct.stayId() == null)) {
      throw new InvalidPaymentException("period");
    }
    if (!Payment.validAmount(direct.amount())) {
      throw new InvalidPaymentException("amount");
    }
    String method = direct.paymentMethod();
    if (method != null
        && (PaymentText.characters(method.strip()) > MAX_METHOD_LENGTH
            || PaymentText.hasUnsafeCharacter(method))) {
      throw new InvalidPaymentException("paymentMethod");
    }
  }

  /** What the payment is for: a period of a contract, or a stay. */
  private record Target(
      @Nullable Long contractId, @Nullable Long stayId, @Nullable YearMonth period) {}

  private Target stayTarget(GuestProfile guest, Long stayId) {
    if (guest.contractType()) {
      throw new InvalidPaymentException("stayId");
    }
    return new Target(null, stayId, null);
  }

  /**
   * The contract that charges the period, and only if RN-27 lets a voucher be registered for it.
   */
  private Target periodTarget(GuestProfile guest, DirectPayment direct) {
    YearMonth period = direct.period();
    if (period == null || !guest.contractType()) {
      throw new InvalidPaymentException("period");
    }
    LocalDate today = BusinessDay.today(clock);
    Optional<ContractInfo> charging =
        contracts.contractsOf(direct.guestId()).stream()
            .filter(c -> c.terms().periods(today).contains(period))
            .max(Comparator.comparing(c -> c.terms().startDate()));
    if (charging.isEmpty()
        || !charging
            .get()
            .terms()
            .acceptsPayment(period, payments.findAllByContract(charging.get().id()), today)) {
      throw new InvalidPaymentException("period");
    }
    return new Target(charging.get().id(), null, period);
  }

  /** An approved payment is a conflict of its own (RN-02); a pending one asks to be resolved. */
  private void rejectLivePayment(Target target) {
    Optional<Payment> live = liveOf(target);
    if (live.isPresent()) {
      throw conflictOf(live.get().status());
    }
  }

  private Optional<Payment> liveOf(Target target) {
    Long stayId = target.stayId();
    if (stayId != null) {
      return payments.findLiveByStay(stayId);
    }
    Long contractId = target.contractId();
    YearMonth period = target.period();
    if (contractId == null || period == null) {
      return Optional.empty();
    }
    return payments.findLiveByPeriod(contractId, period);
  }

  /** The conflict for the live payment that is there; if it is gone, a neutral one (try again). */
  private static RuntimeException conflictOf(@Nullable PaymentStatus live) {
    if (live == null) {
      return new PaymentConcurrentChangeException();
    }
    return live == PaymentStatus.PENDING
        ? new VoucherAlreadyExistsException()
        : new PaymentAlreadyExistsForPeriodException();
  }

  /** The object goes to the bucket first; if the row then fails, the object is removed. */
  private PaymentView store(DirectPayment direct, Target target, VoucherType type) {
    byte[] content = direct.voucher().content();
    String key = storeFile(content, type);
    boolean removedIfNotCommitted = removeUnlessCommitted(key);
    PaymentView saved = null;
    try {
      saved =
          view(
              payments.insertApproved(
                  new DirectDraft(
                      direct.guestId(),
                      target.contractId(),
                      target.stayId(),
                      target.period(),
                      direct.amount().setScale(2),
                      blankToNull(direct.paymentMethod()),
                      key,
                      FileNames.sanitized(direct.voucher().originalName(), DEFAULT_NAME),
                      type,
                      content.length,
                      clock.instant(),
                      direct.adminId())));
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
   * A failure to clean up must not hide the failure that made the cleanup necessary: it is logged
   * with the key, so the orphan object can be removed by hand.
   */
  private void discard(String key) {
    try {
      files.delete(key);
    } catch (PaymentFileStorageException cleanupFailed) {
      LOGGER.error("The voucher {} stays in the bucket without a payment", key, cleanupFailed);
    }
  }

  private static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }

  private static PaymentView view(StoredPayment stored) {
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
