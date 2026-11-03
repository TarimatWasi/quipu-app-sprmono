package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidFinanceMonthException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidRefundException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.RefundGuestNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.GuestCredit.Source;
import com.tarimatwasi.quipu.payment.domain.MonthlyBalance;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.Refund;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RF-24, RN-38, RN-39 (TAR-240). The pending credit is derived from the payments every time; a
 * refund is checked against it with the guest locked, so two refunds of one guest at the same time
 * are registered one after the other and the second sees the first.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class RefundService implements ManageRefundsUseCase {

  /** NUMERIC(10,2): eight digits before the point. */
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

  private final RefundRepositoryPort refunds;
  private final PaymentRepositoryPort payments;
  private final PaymentGuestPort guests;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final PaymentEnvironmentPort environments;
  private final Clock clock;
  private final Credits credits;

  RefundService(
      RefundRepositoryPort refunds,
      PaymentRepositoryPort payments,
      PaymentGuestPort guests,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      PaymentEnvironmentPort environments,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.guests = guests;
    this.contracts = contracts;
    this.stays = stays;
    this.environments = environments;
    this.clock = clock;
    this.credits = new Credits(payments, contracts, stays, refunds, clock);
  }

  @Override
  @Transactional
  public RefundView create(RefundCommand command) {
    BigDecimal amount = command.amount();
    if (amount.signum() <= 0
        || amount.stripTrailingZeros().scale() > 2
        || amount.compareTo(MAX_AMOUNT) > 0) {
      throw new InvalidRefundException("amount");
    }
    if (command.date().isAfter(BusinessDay.today(clock))) {
      throw new InvalidRefundException("date");
    }
    String method = method(command.method());
    String note = note(command.note());
    Long guestId = command.guestId();
    if (guestId == null) {
      throw new RefundGuestNotFoundException();
    }
    try {
      // The guest stays locked until the commit: a second refund waits and then sees this one.
      guests.lock(guestId);
    } catch (GuestNotFoundException missing) {
      throw new RefundGuestNotFoundException();
    }
    var credit = credits.of(guestId, false).credit();
    Source first = credit.firstPending();
    if (first == null || amount.compareTo(credit.pendingRefund()) > 0) {
      throw new InvalidRefundException("amount");
    }
    Refund refund =
        refunds.insert(guestId, first.paymentId(), amount, command.date(), method, note);
    return views(List.of(refund)).getFirst();
  }

  @Override
  @Transactional(readOnly = true)
  public RefundPage list(YearMonth month, PageQuery page) {
    if (!MonthlyBalance.isValidMonth(month)) {
      throw new InvalidFinanceMonthException();
    }
    CursorPage<Refund> found = refunds.findByMonth(month, page);
    List<RefundView> views = views(found.content());
    return new RefundPage(
        new CursorPage<>(views, found.nextCursor()), refunds.totalOfMonth(month).count());
  }

  /**
   * Every guest with an approved payment is read: a credit always starts with one. A business of
   * this size has few, so the guests are read one by one.
   */
  @Override
  @Transactional(readOnly = true)
  public List<PendingCreditView> pendingCredits() {
    List<Pending> found = new ArrayList<>();
    for (Long guestId : payments.guestsWithApprovedPayments()) {
      Credits.Ledger ledger = credits.of(guestId, false);
      Source first = ledger.credit().firstPending();
      if (first != null) {
        found.add(new Pending(guestId, ledger, first, ledger.credit().pendingRefund()));
      }
    }
    Set<Long> guestIds = new HashSet<>();
    found.forEach(pending -> guestIds.add(pending.guestId()));
    Map<Long, GuestProfile> profiles = guestIds.isEmpty() ? Map.of() : guests.profilesOf(guestIds);
    Map<Long, String> codes = environmentCodes(found);
    Map<Long, StayRef> stayRefs = stayRefs(found);
    return found.stream()
        .sorted(Comparator.comparing(Pending::startedOn).thenComparing(Pending::guestId))
        .map(pending -> pendingView(pending, profiles, codes, stayRefs))
        .toList();
  }

  /** A guest with credit to refund, as it was read. */
  private record Pending(Long guestId, Credits.Ledger ledger, Source first, BigDecimal amount) {

    LocalDate startedOn() {
      Long contractId = first.contractId();
      Long stayId = first.stayId();
      if (contractId != null) {
        ContractInfo contract = ledger.contracts().get(contractId);
        if (contract != null) {
          return contract.terms().startDate();
        }
      }
      if (stayId != null && ledger.stays().containsKey(stayId)) {
        return ledger.stays().get(stayId).startDate();
      }
      return LocalDate.MAX;
    }

    @Nullable Long environmentId(Map<Long, StayRef> stayRefs) {
      Long contractId = first.contractId();
      if (contractId != null) {
        ContractInfo contract = ledger.contracts().get(contractId);
        return contract == null ? null : contract.environmentId();
      }
      StayRef stay = first.stayId() == null ? null : stayRefs.get(first.stayId());
      return stay == null ? null : stay.environmentId();
    }
  }

  private Map<Long, StayRef> stayRefs(List<Pending> found) {
    Set<Long> stayIds = new HashSet<>();
    for (Pending pending : found) {
      Long stayId = pending.first().stayId();
      if (stayId != null) {
        stayIds.add(stayId);
      }
    }
    return stayIds.isEmpty() ? Map.of() : stays.staysByIds(stayIds);
  }

  private Map<Long, String> environmentCodes(List<Pending> found) {
    Map<Long, StayRef> stayRefs = stayRefs(found);
    Set<Long> environmentIds = new HashSet<>();
    for (Pending pending : found) {
      Long environmentId = pending.environmentId(stayRefs);
      if (environmentId != null) {
        environmentIds.add(environmentId);
      }
    }
    return environmentIds.isEmpty() ? Map.of() : environments.codesOf(environmentIds);
  }

  private static PendingCreditView pendingView(
      Pending pending,
      Map<Long, GuestProfile> profiles,
      Map<Long, String> codes,
      Map<Long, StayRef> stayRefs) {
    GuestProfile guest = profiles.get(pending.guestId());
    Source first = pending.first();
    Long environmentId = pending.environmentId(stayRefs);
    Long contractId = first.contractId();
    ContractInfo contract =
        contractId == null ? null : pending.ledger().contracts().get(contractId);
    Long stayId = first.stayId();
    StayRef stay = stayId == null ? null : stayRefs.get(stayId);
    return new PendingCreditView(
        pending.guestId(),
        guest == null ? null : guest.name(),
        guest == null || guest.contractType() ? GuestKind.CONTRACT : GuestKind.TEMPORARY,
        environmentId == null ? null : codes.get(environmentId),
        pending.amount(),
        first.paymentId(),
        first.period(),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        contract == null ? null : contract.endedOn());
  }

  /** The refunds with their guest and the environment their credit came from. */
  private List<RefundView> views(List<Refund> found) {
    if (found.isEmpty()) {
      return List.of();
    }
    Set<Long> guestIds = new HashSet<>();
    found.forEach(refund -> guestIds.add(refund.guestId()));
    Map<Long, GuestProfile> profiles = guests.profilesOf(guestIds);
    Map<Long, Long> environmentOf = environmentsOf(found);
    Map<Long, String> codes =
        environmentOf.isEmpty()
            ? Map.of()
            : environments.codesOf(new HashSet<>(environmentOf.values()));
    return found.stream()
        .map(
            refund -> {
              GuestProfile guest = profiles.get(refund.guestId());
              Long environmentId = environmentOf.get(refund.id());
              return new RefundView(
                  refund.id(),
                  refund.guestId(),
                  guest == null ? null : guest.name(),
                  environmentId == null ? null : codes.get(environmentId),
                  refund.amount(),
                  refund.date(),
                  refund.method(),
                  refund.note(),
                  refund.createdAt());
            })
        .toList();
  }

  /** The environment the credit of each refund came from: of its contract, or of its stay. */
  private Map<Long, Long> environmentsOf(List<Refund> found) {
    Map<Long, Payment> origins = new HashMap<>();
    Set<Long> contractIds = new HashSet<>();
    Set<Long> stayIds = new HashSet<>();
    for (Refund refund : found) {
      payments
          .findById(refund.fromPaymentId())
          .map(StoredPayment::payment)
          .ifPresent(
              payment -> {
                origins.put(refund.id(), payment);
                if (payment.contractId() != null) {
                  contractIds.add(payment.contractId());
                }
                if (payment.stayId() != null) {
                  stayIds.add(payment.stayId());
                }
              });
    }
    Map<Long, Long> contractEnvironments =
        contractIds.isEmpty() ? Map.of() : contracts.environmentsOf(contractIds);
    Map<Long, StayRef> stayRefs = stayIds.isEmpty() ? Map.of() : stays.staysByIds(stayIds);
    Map<Long, Long> environmentOf = new HashMap<>();
    for (Map.Entry<Long, Payment> origin : origins.entrySet()) {
      Payment payment = origin.getValue();
      Long environmentId =
          payment.contractId() != null
              ? contractEnvironments.get(payment.contractId())
              : payment.stayId() == null || !stayRefs.containsKey(payment.stayId())
                  ? null
                  : stayRefs.get(payment.stayId()).environmentId();
      if (environmentId != null) {
        environmentOf.put(origin.getKey(), environmentId);
      }
    }
    return environmentOf;
  }

  private static String method(String method) {
    String stripped = method.strip();
    if (stripped.isEmpty()
        || stripped.codePointCount(0, stripped.length()) > Refund.MAX_METHOD
        || PaymentText.hasUnsafeCharacter(stripped)) {
      throw new InvalidRefundException("method");
    }
    return stripped;
  }

  private static @Nullable String note(@Nullable String note) {
    if (note == null || note.isBlank()) {
      return null;
    }
    // The limit of the contract applies to what the caller sent, before the edges are stripped
    if (note.codePointCount(0, note.length()) > Refund.MAX_NOTE) {
      throw new InvalidRefundException("note");
    }
    return note.strip();
  }
}
