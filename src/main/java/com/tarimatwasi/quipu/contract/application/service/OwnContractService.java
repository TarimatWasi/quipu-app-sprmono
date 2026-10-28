package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import com.tarimatwasi.quipu.contract.application.port.in.OwnContractUseCase;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import java.time.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02 (TAR-170): guest use case, besides the URL rule
public class OwnContractService implements OwnContractUseCase {

  private final ContractRepositoryPort contracts;
  private final ContractFilePort files;
  private final Clock clock;

  OwnContractService(ContractRepositoryPort contracts, ContractFilePort files, Clock clock) {
    this.contracts = contracts;
    this.files = files;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public ContractView ownContract(Long guestId) {
    return contracts
        .findLatestByGuest(guestId)
        .map(contract -> ContractViews.of(contract, ContractViews.today(clock), files))
        .orElseThrow(ContractNotFoundException::new);
  }
}
