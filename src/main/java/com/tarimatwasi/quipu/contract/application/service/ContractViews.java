package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractFileView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractSummaryView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.Parties;
import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort.EnvironmentLabel;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort.GuestLabel;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How a contract is shown, on the day of the request (America/Lima, from the injected clock): with
 * who holds it and where (TAR-237), read once for every contract shown.
 */
final class ContractViews {

  private final ContractFilePort files;
  private final ContractGuestPort guests;
  private final ContractEnvironmentPort environments;
  private final Clock clock;

  ContractViews(
      ContractFilePort files,
      ContractGuestPort guests,
      ContractEnvironmentPort environments,
      Clock clock) {
    this.files = files;
    this.guests = guests;
    this.environments = environments;
    this.clock = clock;
  }

  static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), clock.getZone());
  }

  /** Each contract with its signed file URL, signed here every time and never stored. */
  List<ContractView> views(List<Contract> contracts) {
    LocalDate today = today(clock);
    Labels labels = labels(contracts);
    return contracts.stream()
        .map(
            c ->
                new ContractView(
                    c.id(),
                    c.environmentId(),
                    c.guestId(),
                    labels.parties(c),
                    c.monthlyAmount(),
                    c.paymentDay(),
                    c.graceDays(),
                    c.startDate(),
                    c.endDate(),
                    state(c.statusAt(today)),
                    c.expiringSoon(today),
                    terminatedOn(c),
                    new ContractFileView(c.originalName(), c.sizeBytes(), c.uploadedAt()),
                    signedUrl(c)))
        .toList();
  }

  ContractView view(Contract contract) {
    return views(List.of(contract)).getFirst();
  }

  /** The contracts of a page, without file URL. */
  CursorPage<ContractSummaryView> summaries(CursorPage<Contract> page) {
    LocalDate today = today(clock);
    Labels labels = labels(page.content());
    return page.map(
        c ->
            new ContractSummaryView(
                c.id(),
                c.environmentId(),
                c.guestId(),
                labels.parties(c),
                c.monthlyAmount(),
                c.paymentDay(),
                c.graceDays(),
                c.startDate(),
                c.endDate(),
                state(c.statusAt(today)),
                c.expiringSoon(today),
                terminatedOn(c)));
  }

  /** The labels of the guests and the environments of those contracts, one read each. */
  private Labels labels(List<Contract> contracts) {
    if (contracts.isEmpty()) {
      return new Labels(Map.of(), Map.of());
    }
    Set<Long> guestIds = new HashSet<>();
    Set<Long> environmentIds = new HashSet<>();
    for (Contract contract : contracts) {
      guestIds.add(contract.guestId());
      environmentIds.add(contract.environmentId());
    }
    return new Labels(guests.labelsOf(guestIds), environments.labelsOf(environmentIds));
  }

  /** The labels read; a guest or environment is never deleted, so every contract finds both. */
  private record Labels(Map<Long, GuestLabel> guests, Map<Long, EnvironmentLabel> environments) {

    Parties parties(Contract contract) {
      GuestLabel guest = Objects.requireNonNull(guests.get(contract.guestId()));
      EnvironmentLabel environment =
          Objects.requireNonNull(environments.get(contract.environmentId()));
      return new Parties(
          guest.name(),
          guest.documentType(),
          guest.documentNumber(),
          environment.code(),
          environment.type());
    }
  }

  /** RN-21: the day of Lima the contract was finished by hand; null if it was not. */
  private @Nullable LocalDate terminatedOn(Contract contract) {
    Instant at = contract.terminatedAt();
    return at == null ? null : LocalDate.ofInstant(at, clock.getZone());
  }

  /**
   * TAR-237: a failure of the bucket no longer fails the read of the contract; the file link comes
   * null and the screen offers to try again.
   */
  private @Nullable String signedUrl(Contract contract) {
    try {
      return files.signedUrl(contract.fileKey());
    } catch (ContractFileStorageException e) {
      return null;
    }
  }

  private static ContractState state(ContractStatus status) {
    return ContractState.valueOf(status.name());
  }
}
