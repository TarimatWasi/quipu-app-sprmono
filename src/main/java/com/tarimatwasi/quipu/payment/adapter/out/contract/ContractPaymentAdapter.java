package com.tarimatwasi.quipu.payment.adapter.out.contract;

import com.tarimatwasi.quipu.contract.port.in.ContractTermsUseCase;
import com.tarimatwasi.quipu.contract.port.in.ContractTermsUseCase.ContractTermsView;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The payment module asks the contract module, through its exposed port, for the terms. */
@Component
public class ContractPaymentAdapter implements PaymentContractPort {

  private final ContractTermsUseCase contracts;
  private final Clock clock;

  ContractPaymentAdapter(ContractTermsUseCase contracts, Clock clock) {
    this.contracts = contracts;
    this.clock = clock;
  }

  @Override
  public List<ContractInfo> contractsOf(Long guestId) {
    return contracts.termsOfGuest(guestId).stream().map(this::info).toList();
  }

  @Override
  public Map<Long, Long> environmentsOf(Collection<Long> contractIds) {
    return contracts.environmentsOf(contractIds);
  }

  /**
   * The month a contract was finished in is the one of that instant in the zone of the business.
   */
  private ContractInfo info(ContractTermsView view) {
    return new ContractInfo(
        view.contractId(),
        view.environmentId(),
        ContractTerms.of(
            view.startDate(),
            view.endDate(),
            view.terminatedAt(),
            clock.getZone(),
            view.paymentDay(),
            view.graceDays()),
        view.monthlyAmount());
  }
}
