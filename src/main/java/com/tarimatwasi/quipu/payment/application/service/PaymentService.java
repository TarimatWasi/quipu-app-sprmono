package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotCorrectableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentPeriodNotPayableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentReviewerPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.DebtCalculator;
import com.tarimatwasi.quipu.payment.domain.InvalidRejectionReasonException;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.PaymentReview;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.ProofKind;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

  private static final BigDecimal NO_CREDIT = BigDecimal.ZERO.setScale(2);

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepositoryPort payments;
  private final PaymentFilePort files;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentEnvironmentPort environments;
  private final PaymentStayPort stays;
  private final PaymentReviewerPort reviewers;
  private final Clock clock;
  private final ProofRecorder recorder;
  private final AccountStatus accountStatus;
  private final Credits credits;

  PaymentService(
      PaymentRepositoryPort payments,
      PaymentFilePort files,
      PaymentContractPort contracts,
      PaymentDirectory directory,
      RefundRepositoryPort refunds,
      Clock clock) {
    this.payments = payments;
    this.files = files;
    this.guests = directory.guests();
    this.contracts = contracts;
    this.environments = directory.environments();
    this.stays = directory.stays();
    this.reviewers = directory.reviewers();
    this.clock = clock;
    this.recorder = new ProofRecorder(files, payments);
    this.accountStatus = new AccountStatus(payments, contracts, clock);
    this.credits = new Credits(payments, contracts, stays, refunds, clock);
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
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    Map<Long, StayRef> stayRefs =
        stays.staysByIds(
            rows.stream()
                .map(Payment::stayId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    Set<Long> environmentIds = new HashSet<>(environmentOfContract.values());
    stayRefs.values().stream()
        .map(StayRef::environmentId)
        .filter(Objects::nonNull)
        .forEach(environmentIds::add);
    Map<Long, String> codes = environments.codesOf(environmentIds);
    Map<Long, String> names =
        reviewers.namesOf(
            rows.stream()
                .map(Payment::reviewedById)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    List<QueueItemView> items = new ArrayList<>();
    for (StoredPayment stored : found.content()) {
      GuestProfile guest = profiles.get(stored.payment().guestId());
      if (guest == null) {
        // The table links every payment to a guest; if one is gone anyway, the queue keeps working.
        LOGGER.warn("Payment {} has no guest and is left out of the queue", stored.payment().id());
      } else {
        items.add(queueItem(stored, guest, environmentOfContract, stayRefs, codes, names));
      }
    }
    return new CursorPage<>(items, found.nextCursor());
  }

  private static QueueItemView queueItem(
      StoredPayment stored,
      GuestProfile guest,
      Map<Long, Long> environmentOfContract,
      Map<Long, StayRef> stayRefs,
      Map<Long, String> codes,
      Map<Long, String> names) {
    Payment payment = stored.payment();
    PaymentView view = ProofRecorder.view(stored);
    Long contractId = payment.contractId();
    Long stayId = payment.stayId();
    StayRef stay = stayId == null ? null : stayRefs.get(stayId);
    Long environmentId =
        contractId != null
            ? environmentOfContract.get(contractId)
            : stay == null ? null : stay.environmentId();
    return new QueueItemView(
        payment.id(),
        payment.guestId(),
        guest.name(),
        guest.documentNumber(),
        guest.contractType() ? GuestKind.CONTRACT : GuestKind.TEMPORARY,
        environmentId == null ? null : codes.get(environmentId),
        payment.period(),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        view.status(),
        view.proofCount(),
        view.voucherCount(),
        view.hasCash(),
        reviewer(payment, names),
        payment.submittedAt(),
        stored.createdAt());
  }

  private static @Nullable ReviewerView reviewer(Payment payment, Map<Long, String> names) {
    Long id = payment.reviewedById();
    if (id == null || payment.pending()) {
      return null;
    }
    String name = names.get(id);
    return name == null ? null : new ReviewerView(id, name);
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
    return found.map(ProofRecorder::view);
  }

  /**
   * RF-06: the same periods the guest sees, of a guest of either type and in any state; a temporary
   * guest has no contracts and so no periods. The guest is looked up first, so one that does not
   * exist is a 404.
   */
  @Override
  @Transactional(readOnly = true)
  public List<PeriodView> accountStatus(Long guestId) {
    guests.profileOf(guestId);
    return accountStatus.of(guestId);
  }

  /**
   * RF-06: the periods of the current contract of an environment, active or not. The environment is
   * looked up first, so one that does not exist is a 404.
   */
  @Override
  @Transactional(readOnly = true)
  public List<PeriodView> environmentAccountStatus(Long environmentId) {
    environments.requireExists(environmentId);
    return accountStatus.ofEnvironment(environmentId);
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentDetailView get(Long id) {
    StoredPayment stored = payments.findById(id).orElseThrow(PaymentNotFoundException::new);
    Payment payment = stored.payment();
    GuestProfile guest = guests.profileOf(payment.guestId());
    Charge charge = chargeOf(payment);
    List<ProofView> proofs = new ArrayList<>();
    for (PaymentProof proof : stored.proofs()) {
      proofs.add(ProofRecorder.proofView(proof, signedUrl(proof)));
    }
    Long environmentId = charge.environmentId();
    Long reviewerId = payment.reviewedById();
    Map<Long, String> names = reviewerId == null ? Map.of() : reviewers.namesOf(Set.of(reviewerId));
    return new PaymentDetailView(
        ProofRecorder.view(stored),
        guest.name(),
        guest.documentNumber(),
        guest.contractType() ? GuestKind.CONTRACT : GuestKind.TEMPORARY,
        environmentId == null
            ? null
            : environments.codesOf(Set.of(environmentId)).get(environmentId),
        charge.stayStartDate(),
        charge.stayEndDate(),
        charge.expected(),
        charge.creditApplied(),
        charge.amountDue(),
        charge.lastPeriod(),
        reviewer(payment, names),
        proofs,
        payments.findReviews(id).stream().map(ProofRecorder::reviewView).toList(),
        payment.status() == PaymentStatus.APPROVED
            ? credits.of(payment.guestId(), false).surplusOf(payment)
            : null);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PayablePeriodView> payablePeriods(Long guestId) {
    GuestProfile guest = guests.profileOf(guestId);
    LocalDate today = BusinessDay.today(clock);
    return guest.contractType()
        ? payablePeriodsOfContracts(guestId, today)
        : payableStays(guestId, today);
  }

  private List<PayablePeriodView> payablePeriodsOfContracts(Long guestId, LocalDate today) {
    List<PayablePeriodView> payable = new ArrayList<>();
    for (ContractInfo contract : contracts.contractsOf(guestId)) {
      CreditLedger ledger =
          CreditLedger.of(
              contract.terms(),
              contract.monthlyAmount(),
              payments.findAllByContract(contract.id()),
              today);
      for (CreditLedger.Line line : ledger.lines()) {
        PayableState state = payableState(line.status().state(), line.status().paymentId());
        if (state != null) {
          payable.add(
              new PayablePeriodView(
                  contract.id(),
                  line.period(),
                  null,
                  null,
                  null,
                  state,
                  line.status().dueDate(),
                  line.amount(),
                  line.creditApplied(),
                  line.amountDue(),
                  line.last(),
                  line.status().paymentId()));
        }
      }
    }
    return payable;
  }

  private List<PayablePeriodView> payableStays(Long guestId, LocalDate today) {
    List<PayablePeriodView> payable = new ArrayList<>();
    List<StayTerms> ofGuest =
        stays.staysOfGuest(guestId).stream()
            .sorted(Comparator.comparing(StayTerms::startDate).thenComparing(StayTerms::id))
            .toList();
    for (StayTerms stay : ofGuest) {
      StayPayments.StayStatus status =
          StayPayments.status(stay.endDate(), payments.findAllByStay(stay.id()), today);
      PayableState state = payableState(status.state(), status.paymentId());
      if (state != null) {
        payable.add(
            new PayablePeriodView(
                null,
                null,
                stay.id(),
                stay.startDate(),
                stay.endDate(),
                state,
                stay.endDate(),
                stay.agreedAmount(),
                NO_CREDIT,
                stay.agreedAmount(),
                true,
                status.paymentId()));
      }
    }
    return payable;
  }

  /**
   * A5.1: a paid one is left out; one without a live payment but with a rejected one is REJECTED
   * (it is corrected, not paid again); the rest keep their state.
   */
  private static @Nullable PayableState payableState(PeriodState state, @Nullable Long paymentId) {
    return switch (state) {
      case PAID -> null;
      case UNDER_REVIEW -> PayableState.UNDER_REVIEW;
      case UNPAID -> paymentId == null ? PayableState.UNPAID : PayableState.REJECTED;
      case OVERDUE -> paymentId == null ? PayableState.OVERDUE : PayableState.REJECTED;
    };
  }

  private @Nullable String signedUrl(PaymentProof proof) {
    ProofFile file = proof.file();
    if (proof.kind() != ProofKind.VOUCHER || file == null) {
      return null;
    }
    try {
      return files.signedUrl(file.key(), file.type());
    } catch (PaymentFileStorageException e) {
      throw new PaymentFileUnavailableException(e);
    }
  }

  /**
   * What a payment pays: the monthly amount of the contract less the credit the period takes
   * (RN-38), or the agreed amount of a stay, which takes no credit.
   *
   * @param lastPeriod the last month the contract charges, or a stay
   */
  private record Charge(
      BigDecimal expected,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean lastPeriod,
      @Nullable Long environmentId,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate) {

    static Charge of(
        BigDecimal expected,
        BigDecimal credit,
        boolean lastPeriod,
        @Nullable Long environmentId,
        @Nullable LocalDate start,
        @Nullable LocalDate end) {
      return new Charge(
          expected, credit, expected.subtract(credit), lastPeriod, environmentId, start, end);
    }
  }

  private Charge chargeOf(Payment payment) {
    Long stayId = payment.stayId();
    if (stayId != null) {
      StayRef stay = stays.staysByIds(Set.of(stayId)).get(stayId);
      if (stay == null) {
        throw new IllegalStateException("The stay of payment " + payment.id() + " is gone");
      }
      return Charge.of(
          stay.agreedAmount(),
          NO_CREDIT,
          true,
          stay.environmentId(),
          stay.startDate(),
          stay.endDate());
    }
    ContractInfo contract =
        contracts.contractsOf(payment.guestId()).stream()
            .filter(c -> c.id().equals(payment.contractId()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("The contract of payment " + payment.id()));
    YearMonth period = Objects.requireNonNull(payment.period());
    return Charge.of(
        contract.monthlyAmount(),
        creditFor(contract, period),
        period.equals(contract.terms().lastPeriod()),
        contract.environmentId(),
        null,
        null);
  }

  /** RN-38: the credit the period takes from the ones before it in its contract. */
  private BigDecimal creditFor(ContractInfo contract, YearMonth period) {
    return CreditLedger.of(
            contract.terms(),
            contract.monthlyAmount(),
            payments.findAllByContract(contract.id()),
            BusinessDay.today(clock))
        .creditFor(period, contract.monthlyAmount());
  }

  // --- the review ---

  @Override
  @Transactional
  public PaymentView resolve(Long id, Resolution resolution) {
    boolean approving = resolution.decision() == Decision.APPROVED;
    // TAR-204: an approval reads the terms again with the contracts locked before the payment
    // (lock order: contract, payment), so a termination that commits first is seen.
    // The guest of a payment never changes, so it is read before any lock; a stay has none.
    Optional<List<ContractInfo>> lockedContracts =
        approving
            ? payments.findContractGuestId(id).map(contracts::lockedContractsOf)
            : Optional.empty();
    // The row stays locked until the commit: two reviewers of one payment are decided one by one.
    StoredPayment stored =
        payments.findByIdForUpdate(id).orElseThrow(PaymentNotFoundException::new);
    Payment payment = stored.payment();
    if (!payment.resolvable()) {
      throw new PaymentAlreadyResolvedException();
    }
    Instant now = clock.instant();
    lockedContracts.ifPresent(found -> requireCharged(payment, found));
    Payment resolved;
    if (approving) {
      BigDecimal verified = verify(stored, resolution.verified());
      requireCovered(verified, chargeOf(payment).amountDue());
      for (PaymentProof proof : stored.proofs()) {
        payments.updateProof(withVerified(proof, resolution.verified().get(proof.id())));
      }
      resolved = payment.approve(resolution.reviewerId(), verified, now);
    } else {
      resolved = reject(payment, resolution, now);
    }
    StoredPayment saved = payments.save(resolved);
    payments.addReview(
        id,
        new PaymentReview(
            resolved.status(), resolved.rejectionReason(), now, resolution.reviewerId()));
    return ProofRecorder.view(saved);
  }

  /**
   * RN-16: every proof of the payment, and only those, with a verified amount of 0 or more; their
   * sum is what is approved.
   */
  private static BigDecimal verify(StoredPayment stored, Map<Long, BigDecimal> verified) {
    Set<Long> ids =
        stored.proofs().stream().map(PaymentProof::id).collect(Collectors.toUnmodifiableSet());
    if (!ids.equals(verified.keySet())) {
      throw new InvalidPaymentException("proofs");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal amount : verified.values()) {
      if (!Payment.validVerifiedAmount(amount)) {
        throw new InvalidPaymentException("proofs");
      }
      sum = sum.add(amount);
    }
    return sum.setScale(2);
  }

  /** RN-16: a short payment is not approved (nor registered); it is rejected with a reason. */
  private static void requireCovered(BigDecimal paid, BigDecimal due) {
    if (paid.compareTo(due) < 0) {
      throw new InvalidPaymentException("proofs");
    }
  }

  private static PaymentProof withVerified(PaymentProof proof, @Nullable BigDecimal verified) {
    return new PaymentProof(
        proof.id(),
        proof.kind(),
        proof.declaredAmount(),
        verified == null ? null : verified.setScale(2),
        proof.method(),
        proof.file(),
        proof.uploadedBy(),
        proof.receivedOn(),
        proof.addedAt());
  }

  /** The contract of a period payment still charges its month (RN-27 after D-31). */
  private void requireCharged(Payment payment, List<ContractInfo> lockedContracts) {
    Long contractId = payment.contractId();
    YearMonth period = payment.period();
    if (contractId == null || period == null) {
      return;
    }
    LocalDate today = BusinessDay.today(clock);
    boolean charged =
        lockedContracts.stream()
            .anyMatch(c -> c.id().equals(contractId) && c.terms().acceptsPayment(period, today));
    if (!charged) {
      throw new PaymentPeriodNotPayableException();
    }
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

  // --- the correction by the administrator (A5.2, TAR-239) ---

  @Override
  @Transactional
  public PaymentView correct(Long adminId, Long id, List<ProofEdit> proofs) {
    ProofRecorder.checkEdits(proofs);
    Map<Integer, VoucherType> types = ProofRecorder.typesOfEdits(proofs);
    StoredPayment stored =
        payments.findByIdForUpdate(id).orElseThrow(PaymentNotFoundException::new);
    if (!stored.payment().resolvable()) {
      throw new PaymentNotCorrectableException();
    }
    return ProofRecorder.view(
        recorder.correct(stored, proofs, types, ProofAuthor.ADMIN, false, clock.instant()));
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
    // D-27, CU-28: an inactive guest is reactivated once its debt is paid, so the administrator
    // may still pay the periods of a contract it owes; anything else needs an active guest.
    boolean settlingDebt = guest.inactive() && guest.contractType() && stayId == null;
    if (!guest.active() && !settlingDebt) {
      throw new GuestNotActiveException(direct.guestId());
    }
    Target target = stayId != null ? stayTarget(guest, stayId) : periodTarget(guest, direct);
    Map<Integer, VoucherType> types = ProofRecorder.typesOfUploads(direct.proofs());
    rejectLivePayment(target);
    // RN-16, TAR-239: a short payment is not registered.
    requireCovered(ProofRecorder.declaredSum(direct.proofs()), target.amountDue());
    Instant now = clock.instant();
    LocalDate receivedOn =
        direct.receivedOn() == null ? BusinessDay.today(clock) : direct.receivedOn();
    return ProofRecorder.view(
        recorder.insert(
            direct.guestId(),
            direct.proofs(),
            types,
            ProofAuthor.ADMIN,
            receivedOn,
            true,
            proofs ->
                new NewPayment(
                    direct.guestId(),
                    target.contractId(),
                    target.stayId(),
                    target.period(),
                    PaymentStatus.APPROVED,
                    ProofAuthor.ADMIN,
                    proofs,
                    now,
                    now,
                    direct.adminId())));
  }

  /**
   * The proofs, the choice of period or stay and the day received are checked before any lookup.
   */
  private void validate(DirectPayment direct) {
    if ((direct.period() == null) == (direct.stayId() == null)) {
      throw new InvalidPaymentException("period");
    }
    ProofRecorder.checkUploads(direct.proofs());
    LocalDate receivedOn = direct.receivedOn();
    if (receivedOn != null && receivedOn.isAfter(BusinessDay.today(clock))) {
      throw new InvalidPaymentException("receivedOn");
    }
  }

  /** What the payment is for (a period of a contract, or a stay) and what it must cover. */
  private record Target(
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amountDue) {}

  private Target stayTarget(GuestProfile guest, Long stayId) {
    if (guest.contractType()) {
      throw new InvalidPaymentException("stayId");
    }
    StayRef stay = stays.staysByIds(Set.of(stayId)).get(stayId);
    if (stay == null) {
      throw new IllegalStateException("Stay " + stayId + " was found and is gone");
    }
    return new Target(null, stayId, null, stay.agreedAmount());
  }

  /**
   * The contract whose period is paid (RN-27 after D-31): any contract of the guest that charges
   * the month, in force or not. When two charge it (a renewal), the newest one that still owes it,
   * so that every month in debt can be settled; else the newest one, whose payment answers 409. The
   * terms are read with the rows of the contracts locked until the commit (TAR-192): a termination
   * that commits first is seen, and one that comes later waits for this payment.
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
    BigDecimal due = target.monthlyAmount().subtract(creditFor(target, period));
    // RN-38: a period the credit covers whole is already paid.
    if (due.signum() <= 0) {
      throw new PaymentAlreadyExistsForPeriodException();
    }
    return new Target(target.id(), null, period, due);
  }

  private boolean owes(ContractInfo contract, YearMonth period, LocalDate today) {
    return DebtCalculator.debt(
            contract.terms(),
            contract.monthlyAmount(),
            payments.findAllByContract(contract.id()),
            today)
        .stream()
        .anyMatch(d -> d.period().equals(period));
  }

  private void rejectLivePayment(Target target) {
    Long stayId = target.stayId();
    Long contractId = target.contractId();
    YearMonth period = target.period();
    if (stayId != null) {
      ProofRecorder.rejectLive(payments.findLiveByStay(stayId).orElse(null));
    } else if (contractId != null && period != null) {
      ProofRecorder.rejectLive(payments.findLiveByPeriod(contractId, period).orElse(null));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public StayChargeView stayCharge(Long stayId, LocalDate endDate) {
    StayPayments.StayStatus status =
        StayPayments.status(endDate, payments.findAllByStay(stayId), BusinessDay.today(clock));
    return new StayChargeView(status.state().name(), status.paymentId());
  }
}
