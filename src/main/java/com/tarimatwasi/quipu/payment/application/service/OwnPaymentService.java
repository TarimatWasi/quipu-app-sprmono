package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofEdit;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ReviewView;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveContractException;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveStayException;
import com.tarimatwasi.quipu.payment.application.port.in.NoStayYetException;
import com.tarimatwasi.quipu.payment.application.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotCorrectableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.OwnGuest;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.Standing;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentReview;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import com.tarimatwasi.quipu.payment.domain.StayPayments.StayStatus;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OwnPaymentService implements OwnPaymentsUseCase {

  private final PaymentRepositoryPort payments;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final Clock clock;
  private final ProofRecorder recorder;
  private final AccountStatus accountStatus;

  OwnPaymentService(
      PaymentRepositoryPort payments,
      PaymentFilePort files,
      PaymentGuestPort guests,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      Clock clock) {
    this.payments = payments;
    this.guests = guests;
    this.contracts = contracts;
    this.stays = stays;
    this.clock = clock;
    this.recorder = new ProofRecorder(files, payments);
    this.accountStatus = new AccountStatus(payments, contracts, clock);
  }

  // --- the state of the payments ---

  @Override
  @Transactional(readOnly = true)
  public List<PeriodView> accountStatus(Long guestId) {
    admitted(guestId, true);
    return accountStatus.of(guestId);
  }

  @Override
  @Transactional(readOnly = true)
  public StayStatusView stayStatus(Long guestId) {
    admitted(guestId, false);
    LocalDate today = BusinessDay.today(clock);
    StayTerms stay = stays.relevantStayOf(guestId, today).orElseThrow(NoStayYetException::new);
    StayStatus status =
        StayPayments.status(stay.endDate(), payments.findAllByStay(stay.id()), today);
    return new StayStatusView(
        stay.id(),
        stay.startDate(),
        stay.endDate(),
        state(status.state()),
        stay.agreedAmount(),
        status.paymentId(),
        status.rejectionReason());
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<GuestPaymentView> history(Long guestId, PageQuery page) {
    admitted(guestId, true);
    CursorPage<StoredPayment> found = payments.findHistory(guestId, page);
    Map<Long, List<PaymentReview>> reviews =
        payments.findReviewsOf(found.content().stream().map(s -> s.payment().id()).toList());
    Terms terms = termsOf(guestId, true);
    return found.map(
        stored -> guestView(stored, reviews.getOrDefault(stored.payment().id(), List.of()), terms));
  }

  @Override
  @Transactional(readOnly = true)
  public GuestPaymentView payment(Long guestId, Long paymentId, boolean contractType) {
    admitted(guestId, contractType);
    StoredPayment stored = ownPayment(guestId, payments.findById(paymentId).orElse(null));
    return guestView(stored, payments.findReviews(paymentId), termsOf(guestId, contractType));
  }

  /** RN-09: a payment of another guest is, for this one, a payment that does not exist. */
  private static StoredPayment ownPayment(Long guestId, @Nullable StoredPayment stored) {
    if (stored == null || !stored.payment().guestId().equals(guestId)) {
      throw new PaymentNotFoundException();
    }
    return stored;
  }

  /** The amounts and dates the payments of the guest are read with. */
  private record Terms(Map<Long, BigDecimal> monthlyAmounts, Map<Long, StayTerms> stays) {}

  private Terms termsOf(Long guestId, boolean contractType) {
    Map<Long, BigDecimal> monthly = new HashMap<>();
    Map<Long, StayTerms> byId = new HashMap<>();
    if (contractType) {
      contracts.contractsOf(guestId).forEach(c -> monthly.put(c.id(), c.monthlyAmount()));
    } else {
      stays.staysOf(guestId).forEach(s -> byId.put(s.id(), s));
    }
    return new Terms(monthly, byId);
  }

  /** What the guest sees: never who reviewed it (TAR-230) nor a voucher URL. */
  private static GuestPaymentView guestView(
      StoredPayment stored, List<PaymentReview> reviews, Terms terms) {
    Payment payment = stored.payment();
    Long stayId = payment.stayId();
    StayTerms stay = stayId == null ? null : terms.stays().get(stayId);
    Long contractId = payment.contractId();
    BigDecimal expected =
        stay != null
            ? stay.agreedAmount()
            : contractId == null
                ? payment.amount()
                : terms.monthlyAmounts().getOrDefault(contractId, payment.amount());
    // RN-38: the credit arrives with TAR-239 part 2; until then nothing is discounted.
    BigDecimal credit = BigDecimal.ZERO.setScale(2);
    List<ReviewView> decisions = reviews.stream().map(ProofRecorder::reviewView).toList();
    return new GuestPaymentView(
        payment.id(),
        contractId,
        stayId,
        payment.period(),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        ProofRecorder.state(payment.status()),
        payment.amount(),
        payment.verifiedAmount(),
        credit,
        expected.subtract(credit),
        payment.rejectionReason(),
        payment.submittedAt(),
        stored.createdAt(),
        stored.proofs().stream().map(p -> ProofRecorder.proofView(p, null)).toList(),
        decisions);
  }

  private static ChargeState state(PeriodState state) {
    return ChargeState.valueOf(state.name());
  }

  // --- sending a payment ---

  @Override
  @Transactional
  public PaymentView uploadForPeriod(PeriodPayment payment) {
    Long guestId = payment.guestId();
    OwnGuest guest = admitted(guestId, true);
    ProofRecorder.checkUploads(payment.proofs());
    requireActive(guest);
    LocalDate today = BusinessDay.today(clock);
    // RN-27: only a contract in force takes a payment of the guest; an older one that ended is
    // settled by the administrator (direct payment). Of those in force, the one that charges the
    // period: a renewal loaded ahead of time is in force too but charges nothing yet.
    List<ContractInfo> inForce =
        contracts.contractsOf(guestId).stream().filter(c -> c.terms().activeOn(today)).toList();
    if (inForce.isEmpty()) {
      throw new NoActiveContractException();
    }
    YearMonth period = payment.period();
    ContractInfo contract =
        inForce.stream()
            .filter(c -> c.terms().periods(today).contains(period))
            .max(Comparator.comparing(c -> c.terms().startDate()))
            .orElseThrow(() -> new InvalidPaymentException("period"));
    Map<Integer, VoucherType> types = typesOf(payment.proofs());
    ProofRecorder.rejectLive(payments.findLiveByPeriod(contract.id(), period));
    return send(guestId, payment.proofs(), types, contract.id(), null, period);
  }

  @Override
  @Transactional
  public PaymentView uploadForStay(StayPayment payment) {
    Long guestId = payment.guestId();
    OwnGuest guest = admitted(guestId, false);
    ProofRecorder.checkUploads(payment.proofs());
    requireActive(guest);
    LocalDate today = BusinessDay.today(clock);
    StayTerms stay =
        StayPayments.voucherStay(
                stays.staysOf(guestId), id -> payments.findLiveByStay(id).isPresent(), today)
            .orElseThrow(NoActiveStayException::new);
    Map<Integer, VoucherType> types = typesOf(payment.proofs());
    ProofRecorder.rejectLive(payments.findLiveByStay(stay.id()));
    return send(guestId, payment.proofs(), types, null, stay.id(), null);
  }

  private static Map<Integer, VoucherType> typesOf(List<ProofUpload> proofs) {
    return ProofRecorder.typesOfUploads(proofs);
  }

  private PaymentView send(
      Long guestId,
      List<ProofUpload> proofs,
      Map<Integer, VoucherType> types,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period) {
    Instant now = clock.instant();
    return ProofRecorder.view(
        recorder.insert(
            guestId,
            proofs,
            types,
            ProofAuthor.GUEST,
            null,
            false,
            added ->
                new NewPayment(
                    guestId,
                    contractId,
                    stayId,
                    period,
                    PaymentStatus.PENDING,
                    ProofAuthor.GUEST,
                    added,
                    now,
                    null,
                    null)));
  }

  // --- correcting a rejected payment (H3, T2 «Enviar de nuevo», RN-05) ---

  @Override
  @Transactional
  public PaymentView correct(
      Long guestId, Long paymentId, boolean contractType, List<ProofEdit> proofs) {
    OwnGuest guest = admitted(guestId, contractType);
    ProofRecorder.checkEdits(proofs);
    Map<Integer, VoucherType> types = ProofRecorder.typesOfEdits(proofs);
    // The row stays locked until the commit: a correction and a review run one after the other.
    StoredPayment stored = ownPayment(guestId, payments.findByIdForUpdate(paymentId).orElse(null));
    requireActive(guest);
    if (stored.payment().status() != PaymentStatus.REJECTED) {
      throw new PaymentNotCorrectableException();
    }
    return ProofRecorder.view(
        recorder.correct(
            stored,
            proofs,
            types,
            ProofAuthor.GUEST,
            true,
            Objects.requireNonNull(clock.instant())));
  }

  // --- who may ---

  /**
   * RN-09, RN-33: the route belongs to one type of guest, and none is used before the onboarding
   * ends. Both are checked before anything else, so that a guest of the other type learns nothing.
   */
  private OwnGuest admitted(Long guestId, boolean contractType) {
    OwnGuest guest = guests.ownGuest(guestId);
    if (guest.contractType() != contractType) {
      throw new OtherGuestTypeException();
    }
    // RN-33: a guest that has not completed the onboarding never gets here; the guard of the guest
    // areas in the BFF refuses it first, in one place for every route.
    return guest;
  }

  /** RN-35: an inactive guest takes no new payment. */
  private static void requireActive(OwnGuest guest) {
    if (guest.standing() != Standing.ACTIVE) {
      throw new GuestNotActiveException(guest.id());
    }
  }
}
