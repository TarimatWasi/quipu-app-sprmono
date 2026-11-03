package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.QueueItemView;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.PendingCreditView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.GuestContract;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.NotActivated;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.PendingGuest;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.ChargedStay;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.PaymentsOverview;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HU-A04, TAR-236: the states are the ones of the account status (RN-06), derived by the same terms
 * of the contracts and of the stays, with the credit read in (RN-38). Every contract and every stay
 * is read once, and their payments in one read per list, never one per contract or stay; a business
 * of this size has a few dozen of each.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class AdminDashboardService implements AdminDashboardUseCase {

  private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

  private final PaymentRepositoryPort payments;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final PaymentGuestPort guests;
  private final PaymentEnvironmentPort environments;
  private final ManagePaymentsUseCase queue;
  private final ManageRefundsUseCase refunds;
  private final Clock clock;

  AdminDashboardService(
      PaymentRepositoryPort payments,
      PaymentContractPort contracts,
      PaymentDirectory directory,
      ManagePaymentsUseCase queue,
      ManageRefundsUseCase refunds,
      Clock clock) {
    this.payments = payments;
    this.contracts = contracts;
    this.stays = directory.stays();
    this.guests = directory.guests();
    this.environments = directory.environments();
    this.queue = queue;
    this.refunds = refunds;
    this.clock = clock;
  }

  /** A charge before the guest and the environment are named. */
  private record Charge(
      Long guestId,
      boolean contractType,
      @Nullable Long environmentId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal amount,
      LocalDate dueDate,
      PeriodState state,
      @Nullable Long paymentId) {}

  /** Everything the home shows, before the guests and the environments are named. */
  private record Read(
      List<Charge> ofToday,
      List<Charge> overdue,
      List<ChargedStay> stays,
      List<Payment> rejected,
      @Nullable Payment oldestRejected,
      List<GuestContract> expiring) {}

  /** The names of the guests and the codes of the environments the home shows. */
  private record Names(Map<Long, GuestProfile> profiles, Map<Long, String> codes) {}

  @Override
  @Transactional(readOnly = true)
  public DashboardView dashboard() {
    Read read = read(BusinessDay.today(clock));
    Names names = names(read);
    PaymentsOverview overview =
        PaymentsOverview.of(read.ofToday().stream().map(Charge::state).toList());
    List<QueueItemView> pending =
        queue.list(PaymentState.PENDING, new PageQuery(null, MAX_PENDING_VOUCHERS)).content();
    // Read after the page: a voucher that arrives in between is counted, never one listed more.
    long pendingTotal = Math.max(payments.countByStatus(PaymentStatus.PENDING), pending.size());
    List<PendingCreditView> credits = refunds.pendingCredits();
    return new DashboardView(
        overview.upToDate(),
        overview.underReview(),
        overview.pending(),
        overview.overdue(),
        pending,
        pendingTotal,
        read.ofToday().stream().map(charge -> view(charge, names)).toList(),
        new OverdueView(
            read.overdue().size(),
            sum(read.overdue().stream().map(Charge::amount).toList()),
            read.overdue().stream()
                .limit(MAX_OVERDUE_LISTED)
                .map(charge -> view(charge, names))
                .toList()),
        new RejectedView(
            read.rejected().size(),
            rejectedView(read.oldestRejected(), names.profiles(), read.stays())),
        new RefundsView(
            credits.size(), sum(credits.stream().map(PendingCreditView::amount).toList())),
        expiringView(read.expiring(), names),
        notActivated());
  }

  private Read read(LocalDate today) {
    List<Charge> ofToday = new ArrayList<>();
    List<Charge> overdue = new ArrayList<>();
    List<GuestContract> allContracts = contracts.allContracts();
    readContracts(allContracts, today, ofToday, overdue);
    List<ChargedStay> allStays = stays.allStays();
    readStays(allStays, today, ofToday, overdue);
    overdue.sort(Comparator.comparing(Charge::dueDate).thenComparing(Charge::guestId));
    List<Payment> rejected = payments.findByStatus(PaymentStatus.REJECTED);
    Payment oldestRejected =
        rejected.stream()
            .min(
                Comparator.comparing(
                        Payment::reviewedAt,
                        Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                    .thenComparing(Payment::id))
            .orElse(null);
    return new Read(
        ofToday, overdue, allStays, rejected, oldestRejected, expiring(allContracts, today));
  }

  /** One read of the guests and one of the environments, for what the home names. */
  private Names names(Read read) {
    Set<Long> guestIds = new HashSet<>();
    read.ofToday().forEach(charge -> guestIds.add(charge.guestId()));
    read.overdue().stream()
        .limit(MAX_OVERDUE_LISTED)
        .forEach(charge -> guestIds.add(charge.guestId()));
    Payment oldest = read.oldestRejected();
    if (oldest != null) {
      guestIds.add(oldest.guestId());
    }
    if (!read.expiring().isEmpty()) {
      guestIds.add(read.expiring().get(0).guestId());
    }
    Set<Long> environmentIds = new HashSet<>();
    read.ofToday().forEach(charge -> addIfPresent(environmentIds, charge.environmentId()));
    read.overdue().forEach(charge -> addIfPresent(environmentIds, charge.environmentId()));
    read.expiring().forEach(c -> environmentIds.add(c.contract().environmentId()));
    return new Names(
        guestIds.isEmpty() ? Map.of() : guests.profilesOf(guestIds),
        environmentIds.isEmpty() ? Map.of() : environments.codesOf(environmentIds));
  }

  private static BigDecimal sum(List<BigDecimal> amounts) {
    BigDecimal total = NONE;
    for (BigDecimal amount : amounts) {
      total = total.add(amount);
    }
    return total;
  }

  /** The period of the current month of each active contract, and every overdue period. */
  private void readContracts(
      List<GuestContract> all, LocalDate today, List<Charge> ofToday, List<Charge> overdue) {
    if (all.isEmpty()) {
      return;
    }
    Map<Long, List<Payment>> byContract =
        payments.findAllByContracts(all.stream().map(c -> c.contract().id()).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.contractId())));
    YearMonth month = YearMonth.from(today);
    for (GuestContract owned : all) {
      ContractInfo contract = owned.contract();
      CreditLedger ledger =
          CreditLedger.of(
              contract.terms(),
              contract.monthlyAmount(),
              byContract.getOrDefault(contract.id(), List.of()),
              today);
      for (CreditLedger.Line line : ledger.lines()) {
        Charge charge =
            new Charge(
                owned.guestId(),
                true,
                contract.environmentId(),
                contract.id(),
                null,
                line.period(),
                null,
                null,
                line.amountDue(),
                line.status().dueDate(),
                line.status().state(),
                line.status().paymentId());
        if (line.period().equals(month) && contract.terms().activeOn(today)) {
          ofToday.add(charge);
        }
        if (line.status().state() == PeriodState.OVERDUE) {
          overdue.add(charge);
        }
      }
    }
  }

  /** The stay in force today of each guest, and every overdue stay. */
  private void readStays(
      List<ChargedStay> all, LocalDate today, List<Charge> ofToday, List<Charge> overdue) {
    if (all.isEmpty()) {
      return;
    }
    Map<Long, List<Payment>> byStay =
        payments.findAllByStays(all.stream().map(s -> s.stay().id()).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.stayId())));
    for (ChargedStay owned : all) {
      StayRef stay = owned.stay();
      StayPayments.StayStatus status =
          StayPayments.status(stay.endDate(), byStay.getOrDefault(stay.id(), List.of()), today);
      Charge charge =
          new Charge(
              owned.guestId(),
              false,
              stay.environmentId(),
              null,
              stay.id(),
              null,
              stay.startDate(),
              stay.endDate(),
              stay.agreedAmount(),
              stay.endDate(),
              status.state(),
              status.paymentId());
      if (!stay.startDate().isAfter(today) && !stay.endDate().isBefore(today)) {
        ofToday.add(charge);
      }
      if (status.state() == PeriodState.OVERDUE) {
        overdue.add(charge);
      }
    }
  }

  /** RN-07: the active contracts that end within the days ahead, the one ending first first. */
  private static List<GuestContract> expiring(List<GuestContract> all, LocalDate today) {
    LocalDate limit = today.plusDays(EXPIRING_DAYS);
    return all.stream()
        .filter(c -> c.contract().terms().activeOn(today))
        .filter(
            c -> {
              LocalDate end = c.contract().terms().endDate();
              return end != null && !end.isAfter(limit);
            })
        .sorted(
            Comparator.comparing(
                    (GuestContract c) -> Objects.requireNonNull(c.contract().terms().endDate()))
                .thenComparing(c -> c.contract().id()))
        .toList();
  }

  private static ExpiringView expiringView(List<GuestContract> expiring, Names names) {
    Map<Long, GuestProfile> profiles = names.profiles();
    Map<Long, String> codes = names.codes();
    if (expiring.isEmpty()) {
      return new ExpiringView(0, null);
    }
    GuestContract first = expiring.get(0);
    GuestProfile guest = profiles.get(first.guestId());
    return new ExpiringView(
        expiring.size(),
        new ExpiringContractView(
            first.contract().id(),
            guest == null ? null : guest.name(),
            codes.get(first.contract().environmentId()),
            Objects.requireNonNull(first.contract().terms().endDate())));
  }

  private static @Nullable RejectedPaymentView rejectedView(
      @Nullable Payment oldest, Map<Long, GuestProfile> profiles, List<ChargedStay> allStays) {
    if (oldest == null) {
      return null;
    }
    GuestProfile guest = profiles.get(oldest.guestId());
    Long stayId = oldest.stayId();
    StayRef stay =
        stayId == null
            ? null
            : allStays.stream()
                .map(ChargedStay::stay)
                .filter(s -> s.id().equals(stayId))
                .findFirst()
                .orElse(null);
    return new RejectedPaymentView(
        oldest.id(),
        guest == null ? null : guest.name(),
        oldest.period(),
        stay == null ? null : stay.startDate(),
        stay == null ? null : stay.endDate(),
        Objects.requireNonNullElse(oldest.reviewedAt(), oldest.submittedAt()));
  }

  private NotActivatedView notActivated() {
    NotActivated found = guests.notActivated();
    PendingGuest first = found.first();
    return new NotActivatedView(
        found.count(),
        found.expired(),
        first == null
            ? null
            : new PendingGuestView(
                first.id(), first.documentType(), first.documentNumber(), first.accessExpiresAt()));
  }

  private static ChargeView view(Charge charge, Names names) {
    Map<Long, GuestProfile> profiles = names.profiles();
    Map<Long, String> codes = names.codes();
    // A guest is never deleted (the foreign keys hold it), so every charge has its profile.
    GuestProfile guest = Objects.requireNonNull(profiles.get(charge.guestId()));
    Long environmentId = charge.environmentId();
    boolean open = charge.state() == PeriodState.UNPAID || charge.state() == PeriodState.OVERDUE;
    return new ChargeView(
        charge.guestId(),
        guest.name(),
        guest.documentType(),
        guest.documentNumber(),
        charge.contractType() ? GuestKind.CONTRACT : GuestKind.TEMPORARY,
        environmentId == null ? null : codes.get(environmentId),
        charge.contractId(),
        charge.stayId(),
        charge.period(),
        charge.stayStartDate(),
        charge.stayEndDate(),
        charge.amount(),
        charge.dueDate(),
        ChargeState.valueOf(charge.state().name()),
        charge.paymentId(),
        open && charge.paymentId() != null);
  }

  private static void addIfPresent(Set<Long> ids, @Nullable Long id) {
    if (id != null) {
      ids.add(id);
    }
  }
}
