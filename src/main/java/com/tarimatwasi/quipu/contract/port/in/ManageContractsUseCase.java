package com.tarimatwasi.quipu.contract.port.in;

import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** RF-03, RF-09: load, list, read and finish the contracts of the guests. */
public interface ManageContractsUseCase {

  /** What a contract is today; EXPIRED is derived from the end date, not stored (RN-07). */
  enum ContractState {
    ACTIVE,
    EXPIRED,
    TERMINATED
  }

  /** The PDF the administrator uploaded, as the browser named it and as it arrived. */
  final class ContractFile {

    private final String originalName;
    private final byte[] content;

    /** The file with the name the browser gave it. */
    public ContractFile(String originalName, byte[] content) {
      this.originalName = originalName;
      this.content = content;
    }

    /** The name as the browser sent it; it is sanitized before it is stored. */
    public String originalName() {
      return originalName;
    }

    /** The bytes of the file; the caller must not change them. */
    public byte[] content() {
      return content;
    }
  }

  /** What the administrator gives to load a contract. */
  record NewContract(
      Long environmentId,
      Long guestId,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      ContractFile file) {}

  /** A contract with the signed URL of its file (it expires and is not stored). */
  record ContractView(
      Long id,
      Long environmentId,
      Long guestId,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      ContractState status,
      String fileUrl) {}

  /** A contract in a list: no file URL, so listing does not sign one file per row. */
  record ContractSummaryView(
      Long id,
      Long environmentId,
      Long guestId,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      ContractState status,
      boolean expiringSoon) {}

  /**
   * Stores the PDF, then the contract (RF-03). The checks go in this order: the environment and the
   * guest exist, the guest is active, the guest has a contract (RN-13), the environment is free.
   *
   * @throws InvalidContractException a field is out of range
   * @throws InvalidContractDatesException the end date is not after the start date
   * @throws InvalidContractFileException the file is not a PDF by content, or is over 10 MB
   * @throws com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException no such
   *     environment
   * @throws com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException no such guest
   * @throws com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException the guest is not active
   * @throws com.tarimatwasi.quipu.guest.port.in.GuestNotContractException the guest is temporary
   * @throws com.tarimatwasi.quipu.environment.port.in.EnvironmentOccupiedException a contract in
   *     force holds the environment
   */
  ContractView create(NewContract contract);

  /**
   * One page, newest first and read by last index; {@code status == null} lists every contract.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<ContractSummaryView> list(@Nullable ContractState status, PageQuery page);

  /**
   * Reads one contract with the signed URL of its file.
   *
   * @throws ContractNotFoundException if there is no contract with that id
   */
  ContractView get(Long id);

  /**
   * Finishes the contract by hand, freeing the environment (RN-21). One that is already finished or
   * expired stays as it is.
   *
   * @throws ContractNotFoundException if there is no contract with that id
   */
  void terminate(Long id);
}
