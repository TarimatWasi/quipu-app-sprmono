package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.UnknownFieldException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.PagedResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.contract.application.port.in.InvalidContractFileException;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractFile;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractSummaryView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.NewContract;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/** RF-03, RF-09, CU-03, CU-07, CU-12. Only ADMIN reaches /bff/admin/** (RN-08). */
@RestController
public class ContractsBffController {

  /** Every part the multipart form of the contract defines. */
  private static final Set<String> FORM_FIELDS =
      Set.of(
          "file",
          "environmentId",
          "guestId",
          "monthlyAmount",
          "paymentDay",
          "graceDays",
          "startDate",
          "endDate");

  private final ManageContractsUseCase contracts;
  private final IdMasker ids;

  public ContractsBffController(ManageContractsUseCase contracts, IdMasker ids) {
    this.contracts = contracts;
    this.ids = ids;
  }

  /** A contract with the signed URL of its file; the same shape for the guest's own contract. */
  public record ContractResponse(
      UUID id,
      UUID environmentId,
      UUID guestId,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      ContractState status,
      String fileUrl) {
    public static ContractResponse of(ContractView contract, IdMasker ids) {
      return new ContractResponse(
          ids.mask(IdKind.CONTRACT, contract.id()),
          ids.mask(IdKind.ENVIRONMENT, contract.environmentId()),
          ids.mask(IdKind.GUEST, contract.guestId()),
          contract.monthlyAmount(),
          contract.paymentDay(),
          contract.graceDays(),
          contract.startDate(),
          contract.endDate(),
          contract.status(),
          contract.fileUrl());
    }
  }

  public record ContractSummaryResponse(
      UUID id,
      UUID environmentId,
      UUID guestId,
      BigDecimal monthlyAmount,
      int paymentDay,
      int graceDays,
      LocalDate startDate,
      @Nullable LocalDate endDate,
      ContractState status,
      boolean expiringSoon) {
    static ContractSummaryResponse of(ContractSummaryView contract, IdMasker ids) {
      return new ContractSummaryResponse(
          ids.mask(IdKind.CONTRACT, contract.id()),
          ids.mask(IdKind.ENVIRONMENT, contract.environmentId()),
          ids.mask(IdKind.GUEST, contract.guestId()),
          contract.monthlyAmount(),
          contract.paymentDay(),
          contract.graceDays(),
          contract.startDate(),
          contract.endDate(),
          contract.status(),
          contract.expiringSoon());
    }
  }

  @GetMapping("/bff/admin/contracts")
  public PagedResponse<ContractSummaryResponse> list(
      @RequestParam(name = "status", required = false) @Nullable String status,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    return PagedResponse.of(
        contracts.list(state(status), PageParams.query(after, limit)),
        contract -> ContractSummaryResponse.of(contract, ids));
  }

  /** The state filter is a closed list; any other value is a 400 that names the parameter. */
  private static @Nullable ContractState state(@Nullable String status) {
    if (status == null) {
      return null;
    }
    try {
      return ContractState.valueOf(status);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidQueryParameterException("status");
    }
  }

  /**
   * The multipart form of the contract. A missing field or one of the wrong shape is a 400 that
   * names it; the use case checks the file by its content, its size and every range.
   */
  public record ContractForm(
      @NotNull MultipartFile file,
      @NotNull UUID environmentId,
      @NotNull UUID guestId,
      @NotNull BigDecimal monthlyAmount,
      @NotNull Integer paymentDay,
      @NotNull Integer graceDays,
      @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate startDate,
      @DateTimeFormat(iso = ISO.DATE) @Nullable LocalDate endDate) {}

  @PostMapping(path = "/bff/admin/contracts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ContractResponse> create(
      @Valid ContractForm form, MultipartHttpServletRequest request) {
    // Spring binds only what the record declares and silently drops the rest, but the form of the
    // contract is closed: a misspelled optional field must not create a contract with other terms.
    UnknownFieldException.rejectAnyOutside(
        FORM_FIELDS,
        Stream.concat(
                request.getParameterMap().keySet().stream(), request.getFileMap().keySet().stream())
            .toList());
    String name = form.file().getOriginalFilename();
    ContractResponse created =
        ContractResponse.of(
            contracts.create(
                new NewContract(
                    ids.unmask(IdKind.ENVIRONMENT, form.environmentId()),
                    ids.unmask(IdKind.GUEST, form.guestId()),
                    form.monthlyAmount(),
                    form.paymentDay(),
                    form.graceDays(),
                    form.startDate(),
                    form.endDate(),
                    new ContractFile(name == null ? "" : name, content(form.file())))),
            ids);
    return ResponseEntity.created(URI.create("/bff/admin/contracts/" + created.id())).body(created);
  }

  @GetMapping("/bff/admin/contracts/{id}")
  public ResponseEntity<ContractResponse> get(@PathVariable UUID id) {
    // The body carries a freshly signed URL: no cache may keep it (contract v0.7.0).
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ContractResponse.of(contracts.get(ids.unmask(IdKind.CONTRACT, id)), ids));
  }

  @PostMapping("/bff/admin/contracts/{id}/terminate")
  public ResponseEntity<Void> terminate(@PathVariable UUID id) {
    contracts.terminate(ids.unmask(IdKind.CONTRACT, id));
    return ResponseEntity.noContent().build();
  }

  private static byte[] content(MultipartFile file) {
    // RF-15: the declared size is checked before the bytes are read and inspected.
    if (file.getSize() > ManageContractsUseCase.MAX_FILE_BYTES) {
      throw new InvalidContractFileException();
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException unreadable) {
      throw new InvalidContractFileException();
    }
    // TAR-39: the PDF is inspected here, before the use case opens its transaction and locks rows.
    FileKind.inspectAhead(content);
    return content;
  }
}
