package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PeriodStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RF-06: what the guest and the administrator share when they read the state of the periods of a
 * guest or of an environment. Every period that the contracts charge up to the current month, the
 * oldest contract first and each one oldest period first; no contracts, no periods. Who may read it
 * is checked by the caller.
 */
final class AccountStatus {

  private final PaymentRepositoryPort payments;
  private final PaymentContractPort contracts;
  private final Clock clock;

  AccountStatus(PaymentRepositoryPort payments, PaymentContractPort contracts, Clock clock) {
    this.payments = payments;
    this.contracts = contracts;
    this.clock = clock;
  }

  /** The periods of every contract of the guest, whatever its state. */
  List<PeriodView> of(Long guestId) {
    return periodsOf(contracts.contractsOf(guestId));
  }

  /** The periods of the current contract of the environment; none if it has no current one. */
  List<PeriodView> ofEnvironment(Long environmentId) {
    return periodsOf(contracts.currentContractsOf(environmentId));
  }

  private List<PeriodView> periodsOf(List<ContractInfo> read) {
    LocalDate today = BusinessDay.today(clock);
    List<PeriodView> periods = new ArrayList<>();
    if (read.isEmpty()) {
      return periods;
    }
    // The payments of every contract in one read, not one per contract.
    Map<Long, List<Payment>> paid =
        payments.findAllByContracts(read.stream().map(ContractInfo::id).toList()).stream()
            .collect(
                Collectors.groupingBy(payment -> Objects.requireNonNull(payment.contractId())));
    for (ContractInfo contract : read) {
      // RN-38: each period takes the credit the ones before left (TAR-240).
      CreditLedger ledger =
          CreditLedger.of(
              contract.terms(),
              contract.monthlyAmount(),
              paid.getOrDefault(contract.id(), List.of()),
              today);
      for (CreditLedger.Line line : ledger.lines()) {
        PeriodStatus period = line.status();
        periods.add(
            new PeriodView(
                period.period(),
                ChargeState.valueOf(period.state().name()),
                period.dueDate(),
                line.amount(),
                line.creditApplied(),
                line.amountDue(),
                contract.id(),
                period.paymentId(),
                period.rejectionReason()));
      }
    }
    return periods;
  }
}
