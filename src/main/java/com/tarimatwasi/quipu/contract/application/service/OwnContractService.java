package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import com.tarimatwasi.quipu.contract.application.port.in.OwnContractUseCase;
import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.domain.Contract;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02 (TAR-170): guest use case, besides the URL rule
public class OwnContractService implements OwnContractUseCase {

  private static final Comparator<Contract> BY_START =
      Comparator.comparing(Contract::startDate).thenComparing(Contract::id);

  private final ContractRepositoryPort contracts;
  private final Clock clock;
  private final ContractViews views;

  OwnContractService(
      ContractRepositoryPort contracts,
      ContractFilePort files,
      ContractGuestPort guests,
      ContractEnvironmentPort environments,
      Clock clock) {
    this.contracts = contracts;
    this.clock = clock;
    this.views = new ContractViews(files, guests, environments, clock);
  }

  @Override
  @Transactional(readOnly = true)
  public OwnContracts ownContracts(Long guestId) {
    LocalDate today = ContractViews.today(clock);
    List<Contract> all = contracts.findAllByGuest(guestId);
    Contract current = current(all, today).orElseThrow(ContractNotFoundException::new);
    Contract next = next(all, current).orElse(null);
    List<ContractView> shown =
        views.views(next == null ? List.of(current) : List.of(current, next));
    return new OwnContracts(shown.get(0), next == null ? null : shown.get(1));
  }

  /**
   * TAR-237 (RN-07): the one in force today; else the one in force that starts first (it is in
   * force since it was loaded); else the latest one, if all ended.
   */
  private static Optional<Contract> current(List<Contract> all, LocalDate today) {
    Optional<Contract> running =
        all.stream().filter(c -> c.occupies(today) && !c.startDate().isAfter(today)).min(BY_START);
    if (running.isPresent()) {
      return running;
    }
    Optional<Contract> coming = all.stream().filter(c -> c.occupies(today)).min(BY_START);
    return coming.isPresent() ? coming : all.stream().max(BY_START);
  }

  /** A renewal already loaded: in force and starting after the current one ends (RN-20). */
  private static Optional<Contract> next(List<Contract> all, Contract current) {
    LocalDate end = current.endDate();
    if (end == null || current.terminated()) {
      return Optional.empty();
    }
    return all.stream()
        .filter(c -> !c.id().equals(current.id()))
        .filter(c -> !c.terminated() && c.startDate().isAfter(end))
        .min(BY_START);
  }
}
