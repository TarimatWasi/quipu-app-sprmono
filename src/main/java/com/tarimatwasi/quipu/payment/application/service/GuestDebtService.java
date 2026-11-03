package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.DebtCalculator;
import com.tarimatwasi.quipu.payment.domain.DebtPeriod;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.shared.debt.Debt;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The debt of a guest over all its contracts (RN-25, D-27): it belongs to the guest and not to the
 * environment. Two reads whatever the number of contracts: the contracts, and the approved payments
 * of the guest, the only ones that clear a period.
 */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: asked when the administrator loads a contract
public class GuestDebtService implements GuestDebtQuery {

  private final PaymentRepositoryPort payments;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final Clock clock;

  GuestDebtService(
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
  public List<YearMonth> owedPeriods(Long guestId) {
    List<ContractInfo> owned = contracts.contractsOf(guestId);
    if (owned.isEmpty()) {
      return List.of();
    }
    LocalDate today = BusinessDay.today(clock);
    List<Payment> approved = payments.findApprovedOfContracts(guestId);
    // Two contracts can charge the same month (a renewal): the month is owed once.
    TreeSet<YearMonth> owed = new TreeSet<>();
    for (ContractInfo contract : owned) {
      DebtCalculator.debt(contract.terms(), contract.monthlyAmount(), of(contract, approved), today)
          .stream()
          .map(DebtPeriod::period)
          .forEach(owed::add);
    }
    return List.copyOf(owed);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Debt> debts(Long guestId) {
    LocalDate today = BusinessDay.today(clock);
    List<Owed> owed = new ArrayList<>(periodsOwed(guestId, today));
    owed.addAll(staysOwed(guestId, today));
    owed.sort(Comparator.comparing(Owed::dueDate));
    return owed.stream().map(Owed::debt).toList();
  }

  /** A debt with the day it fell due, to order periods and stays together. */
  private record Owed(LocalDate dueDate, Debt debt) {}

  /**
   * The periods owed with what is left after the credit (RN-38). Two contracts that charge the same
   * month (a renewal) add up into one line, as {@link #owedPeriods} counts the month once.
   */
  private List<Owed> periodsOwed(Long guestId, LocalDate today) {
    List<ContractInfo> owned = contracts.contractsOf(guestId);
    if (owned.isEmpty()) {
      return List.of();
    }
    List<Payment> approved = payments.findApprovedOfContracts(guestId);
    Map<YearMonth, BigDecimal> amounts = new TreeMap<>();
    Map<YearMonth, LocalDate> dueDates = new TreeMap<>();
    for (ContractInfo contract : owned) {
      List<Payment> ofContract = of(contract, approved);
      CreditLedger ledger =
          CreditLedger.of(contract.terms(), contract.monthlyAmount(), ofContract, today);
      for (DebtPeriod period : DebtCalculator.debt(contract.terms(), ofContract, today)) {
        CreditLedger.Line line = ledger.line(period.period());
        BigDecimal due = line == null ? contract.monthlyAmount() : line.amountDue();
        if (due.signum() > 0) {
          amounts.merge(period.period(), due, BigDecimal::add);
          dueDates.merge(period.period(), period.dueDate(), (a, b) -> a.isBefore(b) ? a : b);
        }
      }
    }
    return amounts.entrySet().stream()
        .map(
            e ->
                new Owed(
                    Objects.requireNonNull(dueDates.get(e.getKey())),
                    Debt.ofPeriod(e.getKey(), e.getValue())))
        .toList();
  }

  /** The stays whose last day has passed with no approved or pending payment: the agreed amount. */
  private List<Owed> staysOwed(Long guestId, LocalDate today) {
    List<StayTerms> owned = stays.staysOfGuest(guestId);
    if (owned.isEmpty()) {
      return List.of();
    }
    Map<Long, List<Payment>> byStay =
        payments.findAllByStays(owned.stream().map(StayTerms::id).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.stayId())));
    return owned.stream()
        .filter(
            stay ->
                StayPayments.state(stay.endDate(), byStay.getOrDefault(stay.id(), List.of()), today)
                    == PeriodState.OVERDUE)
        .map(
            stay ->
                new Owed(
                    stay.endDate(),
                    Debt.ofStay(stay.id(), stay.startDate(), stay.endDate(), stay.agreedAmount())))
        .toList();
  }

  private static List<Payment> of(ContractInfo contract, List<Payment> approved) {
    return approved.stream().filter(p -> contract.id().equals(p.contractId())).toList();
  }
}
