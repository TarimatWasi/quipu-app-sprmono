package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.DebtCalculator;
import com.tarimatwasi.quipu.payment.domain.InvalidRejectionReasonException;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.port.in.PaymentReasonRequiredException;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class PaymentService implements ManagePaymentsUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepositoryPort payments;
  private final PaymentFilePort files;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentEnvironmentPort environments;
  private final PaymentStayPort stays;
  private final Clock clock;
  private final VoucherRecorder recorder;

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
    this.recorder = new VoucherRecorder(files);
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

  /**
   * Both types of guest have a history: the payments of a period and the payments of a stay come in
   * one list. The page is read before the guest is looked up, so a forged cursor is a 400 even for
   * a guest that does not exist.
   */
  @Override
  @Transactional(readOnly = true)
  public CursorPage<PaymentView> history(Long guestId, PageQuery page) {
    CursorPage<StoredPayment> found = payments.findHistory(guestId, page);
    guests.profileOf(guestId);
    return found.map(VoucherRecorder::view);
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentDetailView get(Long id) {
    StoredPayment stored = payments.findById(id).orElseThrow(PaymentNotFoundException::new);
    return new PaymentDetailView(VoucherRecorder.view(stored), signedUrl(stored));
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
    return VoucherRecorder.view(payments.save(resolved));
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
      throw PaymentText.characters(Payment.cleanReason(reason)) > Payment.MAX_REASON_LENGTH
          ? new InvalidPaymentException("reason")
          : new PaymentReasonRequiredException();
    }
  }

  // --- the payment registered by hand ---

  @Override
  @Transactional
  public PaymentView registerDirect(DirectPayment direct) {
    validate(direct);
    // TAR-38: the guest is locked before its state is read and before the contracts (order: guest,
    // contract, payment), so a deactivation that has not committed yet is waited for, not missed.
    guests.lock(direct.guestId());
    GuestProfile guest = guests.profileOf(direct.guestId());
    Long stayId = direct.stayId();
    if (stayId != null) {
      stays.stayOf(direct.guestId(), stayId);
    }
    // D-27: an inactive guest is reactivated once its debt is paid, so the administrator may still
    // pay the periods of a contract it owes; anything else needs an active guest.
    boolean settlingDebt = guest.inactive() && guest.contractType() && stayId == null;
    if (!guest.active() && !settlingDebt) {
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
    VoucherRecorder.checkAmountAndMethod(direct.amount(), direct.paymentMethod());
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
   * The contract whose period is paid (RN-27 after D-31): any contract of the guest that charges
   * the month, in force or not. When two charge it (a renewal), the newest one that still owes it,
   * so that every month in debt can be settled; else the newest one, whose live payment answers
   * 409. The terms are read with the rows of the contracts locked until the commit (TAR-192): a
   * termination that commits first is seen, and one that comes later waits for this payment.
   */
  private Target periodTarget(GuestProfile guest, DirectPayment direct) {
    YearMonth period = direct.period();
    if (period == null || !guest.contractType()) {
      throw new InvalidPaymentException("period");
    }
    LocalDate today = BusinessDay.today(clock);
    List<ContractInfo> charging =
        contracts.lockedContractsOf(direct.guestId()).stream()
            .filter(c -> c.terms().acceptsPayment(period, today))
            .sorted(Comparator.comparing((ContractInfo c) -> c.terms().startDate()).reversed())
            .toList();
    if (charging.isEmpty()) {
      throw guest.active()
          ? new InvalidPaymentException("period")
          : new GuestNotActiveException(guest.id());
    }
    ContractInfo target =
        charging.size() == 1
            ? charging.get(0)
            : charging.stream()
                .filter(c -> owes(c, period, today))
                .findFirst()
                .orElse(charging.get(0));
    // An inactive guest pays only a month it owes (D-27), never one ahead.
    if (!guest.active() && !owes(target, period, today)) {
      throw new GuestNotActiveException(guest.id());
    }
    return new Target(target.id(), null, period);
  }

  private boolean owes(ContractInfo contract, YearMonth period, LocalDate today) {
    return DebtCalculator.debt(contract.terms(), payments.findAllByContract(contract.id()), today)
        .stream()
        .anyMatch(d -> d.period().equals(period));
  }

  private void rejectLivePayment(Target target) {
    Long stayId = target.stayId();
    Long contractId = target.contractId();
    YearMonth period = target.period();
    if (stayId != null) {
      VoucherRecorder.rejectLive(payments.findLiveByStay(stayId));
    } else if (contractId != null && period != null) {
      VoucherRecorder.rejectLive(payments.findLiveByPeriod(contractId, period));
    }
  }

  private PaymentView store(DirectPayment direct, Target target, VoucherType type) {
    byte[] content = direct.voucher().content();
    return VoucherRecorder.view(
        recorder.store(
            content,
            type,
            key ->
                payments.insertApproved(
                    new DirectDraft(
                        direct.guestId(),
                        target.contractId(),
                        target.stayId(),
                        target.period(),
                        direct.amount().setScale(2),
                        VoucherRecorder.blankToNull(direct.paymentMethod()),
                        key,
                        FileNames.sanitized(
                            direct.voucher().originalName(), VoucherRecorder.DEFAULT_NAME),
                        type,
                        content.length,
                        clock.instant(),
                        direct.adminId()))));
  }
}
