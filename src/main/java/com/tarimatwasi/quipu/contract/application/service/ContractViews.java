package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ContractFileUnavailableException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractSummaryView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import java.time.Clock;
import java.time.LocalDate;

/** How a contract is shown, on the day of the request (America/Lima, from the injected clock). */
final class ContractViews {

  private ContractViews() {}

  static LocalDate today(Clock clock) {
    return LocalDate.ofInstant(clock.instant(), clock.getZone());
  }

  /** The file URL is signed here, every time, and never stored. */
  static ContractView of(Contract contract, LocalDate today, ContractFilePort files) {
    return new ContractView(
        contract.id(),
        contract.environmentId(),
        contract.guestId(),
        contract.monthlyAmount(),
        contract.paymentDay(),
        contract.graceDays(),
        contract.startDate(),
        contract.endDate(),
        state(contract.statusAt(today)),
        signedUrl(contract, files));
  }

  private static String signedUrl(Contract contract, ContractFilePort files) {
    try {
      return files.signedUrl(contract.fileKey());
    } catch (ContractFileStorageException e) {
      throw new ContractFileUnavailableException(e);
    }
  }

  static ContractSummaryView summary(Contract contract, LocalDate today) {
    return new ContractSummaryView(
        contract.id(),
        contract.environmentId(),
        contract.guestId(),
        contract.monthlyAmount(),
        contract.paymentDay(),
        contract.graceDays(),
        contract.startDate(),
        contract.endDate(),
        state(contract.statusAt(today)),
        contract.expiringSoon(today));
  }

  private static ContractState state(ContractStatus status) {
    return ContractState.valueOf(status.name());
  }
}
