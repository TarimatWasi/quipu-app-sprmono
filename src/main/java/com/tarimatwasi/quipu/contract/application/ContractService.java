package com.tarimatwasi.quipu.contract.application;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.Contract.Violation;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.contract.port.in.ContractFileUnavailableException;
import com.tarimatwasi.quipu.contract.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractDatesException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractFileException;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase;
import com.tarimatwasi.quipu.contract.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.contract.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.contract.port.out.ContractGuestPort.GuestProfile;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort.Draft;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotContractException;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02 (TAR-170): admin use case, besides the URL rule
public class ContractService implements ManageContractsUseCase {

  /** RF-15: the PDF of a contract has at most 10 MB. */
  static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractService.class);

  private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  /** VARCHAR(255) of original_name. */
  private static final int MAX_NAME_LENGTH = 255;

  private static final String DEFAULT_NAME = "contrato.pdf";

  private final ContractRepositoryPort contracts;
  private final ContractFilePort files;
  private final ContractEnvironmentPort environments;
  private final ContractGuestPort guests;
  private final Clock clock;

  ContractService(
      ContractRepositoryPort contracts,
      ContractFilePort files,
      ContractEnvironmentPort environments,
      ContractGuestPort guests,
      Clock clock) {
    this.contracts = contracts;
    this.files = files;
    this.environments = environments;
    this.guests = guests;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ContractView create(NewContract contract) {
    validate(contract);
    // The environment stays locked until the commit: two contracts on it are decided one by one.
    environments.lock(contract.environmentId());
    GuestProfile guest = guests.profileOf(contract.guestId());
    if (!guest.active()) {
      throw new GuestNotActiveException(contract.guestId());
    }
    if (!guest.hasContractType()) {
      throw new GuestNotContractException();
    }
    // GUEST_HAS_PENDING_DEBT (RN-25, RN-35) is checked here, between the guest type and the
    // environment, once the payments exist.
    LocalDate today = ContractViews.today(clock);
    if (contracts.existsInForce(contract.environmentId(), today)) {
      throw new EnvironmentOccupiedException();
    }
    return store(contract, today);
  }

  /** The object goes to the bucket first; if the row then fails, the object is removed. */
  private ContractView store(NewContract contract, LocalDate today) {
    String key = storeFile(contract.file().content());
    Contract saved = null;
    try {
      saved =
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
                  sanitizedName(contract.file().originalName()),
                  contract.file().content().length));
    } finally {
      if (saved == null) {
        discard(key);
      }
    }
    return ContractViews.of(saved, today, files);
  }

  private String storeFile(byte[] content) {
    try {
      return files.store(content);
    } catch (ContractFileStorageException e) {
      throw new ContractFileUnavailableException(e);
    }
  }

  /**
   * A failure to clean up must not hide the failure that made the cleanup necessary: it is logged
   * with the key, so the orphan object can be removed by hand.
   */
  private void discard(String key) {
    try {
      files.delete(key);
    } catch (ContractFileStorageException cleanupFailed) {
      LOGGER.error(
          "The contract file {} stays in the bucket without a contract", key, cleanupFailed);
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
    Contract contract = contracts.findById(id).orElseThrow(ContractNotFoundException::new);
    if (contract.occupies(ContractViews.today(clock))) {
      contracts.save(contract.terminate());
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
    if (content.length == 0 || content.length > MAX_FILE_BYTES || !startsWithPdfHeader(content)) {
      throw new InvalidContractFileException();
    }
  }

  private static boolean startsWithPdfHeader(byte[] content) {
    return content.length >= PDF_HEADER.length
        && Arrays.equals(content, 0, PDF_HEADER.length, PDF_HEADER, 0, PDF_HEADER.length);
  }

  /**
   * The name is only a label for the download: no directories (neither / nor \), no control
   * characters, at most 255 characters, never empty.
   */
  static String sanitizedName(String name) {
    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    String clean = base.replaceAll("\\p{Cntrl}", "").strip();
    if (clean.length() > MAX_NAME_LENGTH) {
      clean = clean.substring(0, MAX_NAME_LENGTH);
    }
    return clean.isEmpty() || clean.chars().allMatch(c -> c == '.') ? DEFAULT_NAME : clean;
  }
}
