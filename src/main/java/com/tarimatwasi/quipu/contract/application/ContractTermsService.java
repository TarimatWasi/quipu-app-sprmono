package com.tarimatwasi.quipu.contract.application;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.in.ContractTermsUseCase;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('ADMIN', 'GUEST')") // BE-SPR-SEC-02: for the payments of either side
public class ContractTermsService implements ContractTermsUseCase {

  private final ContractRepositoryPort contracts;
  private final Clock clock;

  ContractTermsService(ContractRepositoryPort contracts, Clock clock) {
    this.contracts = contracts;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ContractTermsView> termsOfGuest(Long guestId) {
    return contracts.findAllByGuest(guestId).stream().map(ContractTermsService::view).toList();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ContractTermsView> lockedTermsOfGuest(Long guestId) {
    return contracts.findAllByGuestForUpdate(guestId).stream()
        .map(ContractTermsService::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, Long> environmentsOf(Collection<Long> contractIds) {
    Map<Long, Long> environments = new HashMap<>();
    if (!contractIds.isEmpty()) {
      contracts.findAllByIds(contractIds).forEach(c -> environments.put(c.id(), c.environmentId()));
    }
    return environments;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ContractTermsView> currentTermsOfEnvironment(Long environmentId) {
    return contracts.findAllInForce(environmentId, ContractViews.today(clock)).stream()
        .map(ContractTermsService::view)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ContractTermsView> termsInForce() {
    return contracts.findAllInForce(ContractViews.today(clock)).stream()
        .map(ContractTermsService::view)
        .toList();
  }

  private static ContractTermsView view(Contract contract) {
    return new ContractTermsView(
        contract.id(),
        contract.environmentId(),
        contract.startDate(),
        contract.endDate(),
        contract.terminatedAt(),
        contract.paymentDay(),
        contract.graceDays(),
        contract.monthlyAmount());
  }
}
