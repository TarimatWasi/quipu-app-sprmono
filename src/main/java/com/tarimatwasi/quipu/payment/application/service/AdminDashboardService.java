package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.PaymentsOverview;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HU-A04: the states are the ones of the account status (RN-06), derived by the same terms of the
 * contracts and of the stays. Every read is one for all the contracts, one for all the stays and
 * one for each list of payments, never one per contract or stay.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class AdminDashboardService implements AdminDashboardUseCase {

  private final PaymentRepositoryPort payments;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final Clock clock;

  AdminDashboardService(
      PaymentRepositoryPort payments,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      Clock clock) {
    this.payments = payments;
    this.contracts = contracts;
    this.stays = stays;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public DashboardView dashboard() {
    LocalDate today = BusinessDay.today(clock);
    List<PeriodState> states = new ArrayList<>();
    states.addAll(ofContracts(today));
    states.addAll(ofStays(today));
    PaymentsOverview overview = PaymentsOverview.of(states);
    List<PaymentView> pending =
        payments
            .findQueue(PaymentStatus.PENDING, new PageQuery(null, MAX_PENDING_VOUCHERS))
            .content()
            .stream()
            .map(VoucherRecorder::view)
            .toList();
    // Read after the page: a voucher that arrives in between is counted, never one listed more.
    long total = Math.max(payments.countByStatus(PaymentStatus.PENDING), pending.size());
    return new DashboardView(
        overview.upToDate(),
        overview.underReview(),
        overview.pending(),
        overview.overdue(),
        pending,
        total);
  }

  /** The period of the current month of each active contract that charges it. */
  private List<PeriodState> ofContracts(LocalDate today) {
    List<ContractInfo> inForce = contracts.contractsInForce();
    if (inForce.isEmpty()) {
      return List.of();
    }
    Map<Long, List<Payment>> byContract =
        payments
            .findAllByContractsAndPeriod(
                inForce.stream().map(ContractInfo::id).toList(), YearMonth.from(today))
            .stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.contractId())));
    List<PeriodState> states = new ArrayList<>();
    for (ContractInfo contract : inForce) {
      PeriodState state =
          contract
              .terms()
              .stateOfCurrentMonth(byContract.getOrDefault(contract.id(), List.of()), today);
      if (state != null) {
        states.add(state);
      }
    }
    return states;
  }

  /** The stay in course of each temporary guest. */
  private List<PeriodState> ofStays(LocalDate today) {
    List<StayTerms> inCourse = stays.staysInForceOn(today);
    if (inCourse.isEmpty()) {
      return List.of();
    }
    Map<Long, List<Payment>> byStay =
        payments.findAllByStays(inCourse.stream().map(StayTerms::id).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.stayId())));
    return inCourse.stream()
        .map(s -> StayPayments.state(s.endDate(), byStay.getOrDefault(s.id(), List.of()), today))
        .toList();
  }
}
