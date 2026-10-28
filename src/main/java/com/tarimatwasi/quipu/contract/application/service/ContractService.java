package com.tarimatwasi.quipu.contract.application.service;

import com.tarimatwasi.quipu.contract.application.port.in.ContractFileUnavailableException;
import com.tarimatwasi.quipu.contract.application.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractDatesException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractException;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractFileException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase;
import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort.GuestProfile;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractRepositoryPort.Draft;
import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.Contract.Violation;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentInactiveException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotContractException;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.FileNames;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class ContractService implements ManageContractsUseCase {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractService.class);

  private static final String DEFAULT_NAME = "contrato.pdf";

  private final ContractRepositoryPort contracts;
  private final ContractFilePort files;
  private final ContractEnvironmentPort environments;
  private final ContractGuestPort guests;
  private final GuestDebtQuery debts;
  private final Clock clock;

  ContractService(
      ContractRepositoryPort contracts,
      ContractFilePort files,
      ContractEnvironmentPort environments,
      ContractGuestPort guests,
      GuestDebtQuery debts,
      Clock clock) {
    this.contracts = contracts;
    this.files = files;
    this.environments = environments;
    this.guests = guests;
    this.debts = debts;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ContractView create(NewContract contract) {
    validate(contract);
    // Both rows stay locked until the commit, in the global order environment, guest, contract,
    // payment: two contracts on one environment, or two of one guest, are decided one by one.
    boolean environmentActive = environments.lock(contract.environmentId());
    guests.lock(contract.guestId());
    GuestProfile guest = guests.profileOf(contract.guestId());
    if (!guest.active()) {
      throw new GuestNotActiveException(contract.guestId());
    }
    if (!guest.hasContractType()) {
      throw new GuestNotContractException();
    }
    // RN-25, RN-35 (D-27): the debt is of the guest, over all its contracts. Read under the lock of
    // the guest, in the order of the contract: guest state, guest type, debt, environment state,
    // environment occupancy (bff.yaml v0.8.0).
    List<YearMonth> owed = debts.owedPeriods(contract.guestId());
    if (!owed.isEmpty()) {
      throw new GuestHasPendingDebtException(owed);
    }
    // TAR-200: read under the lock taken above, so a deactivation cannot slip in before the insert.
    if (!environmentActive) {
      throw new EnvironmentInactiveException();
    }
    LocalDate today = ContractViews.today(clock);
    if (contracts.existsInForce(contract.environmentId(), today)) {
      throw new EnvironmentOccupiedException();
    }
    return store(contract, today);
  }

  /** The object goes to the bucket first; if the row then fails, the object is removed. */
  private ContractView store(NewContract contract, LocalDate today) {
    // ponytail: the upload runs inside the transaction that holds the lock of the environment, so a
    // slow bucket delays other contracts on that same environment (one per upload, at most 10 MB);
    // upload before opening the transaction if that ever hurts. A failure of the commit itself
    // leaves
    // the object orphan: it is rare and only the bucket listing finds it.
    String key = storeFile(contract.file().content());
    ContractView view = null;
    try {
      Contract saved =
          contracts.insert(
              new Draft(
                  contract.environmentId(),
                  contract.guestId(),
                  contract.startDate(),
                  contract.endDate(),
                  contract.monthlyAmount(),
                  contract.paymentDay(),
                  contract.graceDays(),
                  key,
                  FileNames.sanitized(contract.file().originalName(), DEFAULT_NAME),
                  contract.file().content().length));
      // Signing is inside the try: if it fails the transaction rolls back and the object goes too.
      view = ContractViews.of(saved, today, files);
      return view;
    } finally {
      if (view == null) {
        discard(contract.guestId(), key);
      }
    }
  }

  private String storeFile(byte[] content) {
    try {
      return files.store(content);
    } catch (ContractFileStorageException e) {
      throw new ContractFileUnavailableException(e);
    }
  }

  /**
   * A failure to clean up must not hide the failure that made the cleanup necessary. It is logged
   * with the internal id of the guest, never the storage key (TAR-205).
   */
  private void discard(Long guestId, String key) {
    try {
      files.delete(key);
    } catch (ContractFileStorageException cleanupFailed) {
      // TAR-205: the class names only; the message and trace of the SDK name the R2 host
      // (account and bucket) and may name the key.
      Throwable cause = cleanupFailed.getCause();
      LOGGER.error(
          "A contract file of guest {} stays in the bucket without a contract ({}, cause {})",
          guestId,
          cleanupFailed.getClass().getName(),
          cause == null ? "-" : cause.getClass().getName());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public CursorPage<ContractSummaryView> list(@Nullable ContractState status, PageQuery page) {
    LocalDate today = ContractViews.today(clock);
    ContractStatus filter = status == null ? null : ContractStatus.valueOf(status.name());
    return contracts.findAll(filter, today, page).map(c -> ContractViews.summary(c, today));
  }

  @Override
  @Transactional(readOnly = true)
  public ContractView get(Long id) {
    Contract contract = contracts.findById(id).orElseThrow(ContractNotFoundException::new);
    return ContractViews.of(contract, ContractViews.today(clock), files);
  }

  @Override
  @Transactional
  public void terminate(Long id) {
    // The row stays locked until the commit: a second request for the same contract waits, then
    // sees it terminated and answers the same 204 instead of a version conflict.
    Contract contract = contracts.findByIdForUpdate(id).orElseThrow(ContractNotFoundException::new);
    if (contract.occupies(ContractViews.today(clock))) {
      contracts.save(contract.terminate(clock.instant()));
    }
  }

  private static void validate(NewContract contract) {
    Violation violation =
        Contract.violationOf(
            contract.startDate(),
            contract.endDate(),
            contract.monthlyAmount(),
            contract.paymentDay(),
            contract.graceDays());
    if (violation == Violation.DATES_ORDER) {
      throw new InvalidContractDatesException();
    }
    if (violation != null) {
      throw new InvalidContractException(violation.field());
    }
    byte[] content = contract.file().content();
    // RF-15: a PDF by its content, with no active content, as the vouchers (shared.file).
    if (content.length > MAX_FILE_BYTES
        || FileKind.of(content).filter(kind -> kind == FileKind.PDF).isEmpty()) {
      throw new InvalidContractFileException();
    }
  }
}
