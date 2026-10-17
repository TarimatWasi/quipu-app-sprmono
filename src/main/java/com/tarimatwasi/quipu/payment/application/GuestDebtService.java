package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.DebtCalculator;
import com.tarimatwasi.quipu.payment.domain.DebtPeriod;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.TreeSet;
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
  private final Clock clock;

  GuestDebtService(PaymentRepositoryPort payments, PaymentContractPort contracts, Clock clock) {
    this.payments = payments;
    this.contracts = contracts;
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
      List<Payment> ofContract =
          approved.stream().filter(p -> contract.id().equals(p.contractId())).toList();
      DebtCalculator.debt(contract.terms(), ofContract, today).stream()
          .map(DebtPeriod::period)
          .forEach(owed::add);
    }
    return List.copyOf(owed);
  }
}
