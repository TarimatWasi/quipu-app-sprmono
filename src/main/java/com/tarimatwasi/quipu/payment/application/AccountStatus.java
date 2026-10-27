package com.tarimatwasi.quipu.payment.application;

import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.PeriodStatus;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * RF-06: what the guest and the administrator share when they read the state of the periods of a
 * guest. Every period that the contracts of the guest charge up to the current month, the oldest
 * contract first and each one oldest period first; a guest without contracts has none. Who may read
 * it is checked by the caller.
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

  List<PeriodView> of(Long guestId) {
    LocalDate today = BusinessDay.today(clock);
    List<PeriodView> periods = new ArrayList<>();
    for (ContractInfo contract : contracts.contractsOf(guestId)) {
      for (PeriodStatus period :
          contract.terms().statuses(payments.findAllByContract(contract.id()), today)) {
        periods.add(
            new PeriodView(
                period.period(),
                ChargeState.valueOf(period.state().name()),
                period.dueDate(),
                contract.monthlyAmount(),
                contract.id(),
                period.paymentId(),
                period.rejectionReason()));
      }
    }
    return periods;
  }
}
