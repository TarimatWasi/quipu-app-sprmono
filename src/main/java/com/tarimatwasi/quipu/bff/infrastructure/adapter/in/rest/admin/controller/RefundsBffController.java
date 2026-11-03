package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.PagedResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.PendingCreditView;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.RefundCommand;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.RefundPage;
import com.tarimatwasi.quipu.payment.application.port.in.ManageRefundsUseCase.RefundView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-24, RN-38, RN-39 (TAR-240): the refunds and the pending credits. Only ADMIN reaches
 * /bff/admin/** (RN-08); SecurityConfig enforces it.
 */
@RestController
public class RefundsBffController {

  private static final String URL = "/bff/admin/refunds";

  // PostgreSQL cannot store the NUL character in a text column
  private static final String NO_NUL = "(?s)[^\\x00]*";

  /** Read as text so that a value that is not a UUID is a 400 that names the field. */
  private static final String UUID_TEXT =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  /** The use case refuses year 0000, which the pattern lets through. */
  private static final java.util.regex.Pattern MONTH =
      java.util.regex.Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private final ManageRefundsUseCase refunds;
  private final IdMasker ids;

  public RefundsBffController(ManageRefundsUseCase refunds, IdMasker ids) {
    this.refunds = refunds;
    this.ids = ids;
  }

  /** RefundInput; the use case checks the amount, the date, the method and the note. */
  public record RefundRequest(
      @NotNull @Pattern(regexp = UUID_TEXT) String guestId,
      @NotNull BigDecimal amount,
      @NotNull LocalDate date,
      @NotNull @Pattern(regexp = NO_NUL) String method,
      @Nullable @Pattern(regexp = NO_NUL) String note) {}

  /** Refund, with the guest it went to; the nullable fields are always written. */
  public record RefundResponse(
      UUID id,
      UUID guestId,
      @Nullable String guestName,
      @Nullable String environmentCode,
      BigDecimal amount,
      LocalDate date,
      String method,
      @Nullable String note,
      Instant createdAt) {
    static RefundResponse of(RefundView view, IdMasker ids) {
      return new RefundResponse(
          ids.mask(IdKind.REFUND, view.id()),
          ids.mask(IdKind.GUEST, view.guestId()),
          view.guestName(),
          view.environmentCode(),
          view.amount(),
          view.date(),
          view.method(),
          view.note(),
          view.createdAt());
    }
  }

  /** PagedRefunds: one page and how many the month has. */
  public record PagedRefundsResponse(
      List<RefundResponse> content, @Nullable String nextCursor, long totalCount) {}

  /** PendingCredit: a guest with credit to refund and where it came from. */
  public record PendingCreditResponse(
      UUID guestId,
      @Nullable String guestName,
      GuestKind guestType,
      @Nullable String environmentCode,
      BigDecimal amount,
      OriginResponse origin) {
    static PendingCreditResponse of(PendingCreditView view, IdMasker ids) {
      YearMonth period = view.period();
      return new PendingCreditResponse(
          ids.mask(IdKind.GUEST, view.guestId()),
          view.guestName(),
          view.guestType(),
          view.environmentCode(),
          view.amount(),
          new OriginResponse(
              ids.mask(IdKind.PAYMENT, view.paymentId()),
              period == null ? null : period.toString(),
              view.stayStartDate(),
              view.stayEndDate(),
              view.contractEndedOn()));
    }
  }

  /** Where a pending credit came from («Sobró del pago de octubre»). */
  public record OriginResponse(
      UUID paymentId,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable LocalDate contractEndedOn) {}

  @GetMapping(URL)
  public ResponseEntity<PagedRefundsResponse> list(
      @RequestParam("month") String month,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    if (!MONTH.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    RefundPage found = refunds.list(YearMonth.parse(month), PageParams.query(after, limit));
    PagedResponse<RefundResponse> page =
        PagedResponse.of(found.page(), refund -> RefundResponse.of(refund, ids));
    // Money returned to people: no cache keeps it.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new PagedRefundsResponse(page.content(), page.nextCursor(), found.totalCount()));
  }

  @PostMapping(URL)
  public ResponseEntity<RefundResponse> create(@Valid @RequestBody RefundRequest request) {
    RefundResponse created =
        RefundResponse.of(
            refunds.create(
                new RefundCommand(
                    guestOf(request.guestId()),
                    request.amount(),
                    request.date(),
                    request.method(),
                    request.note())),
            ids);
    return ResponseEntity.created(URI.create(URL + "/" + created.id())).body(created);
  }

  /** {@code pending=true} is the only value the contract supports for now. */
  @GetMapping("/bff/admin/credits")
  public ResponseEntity<List<PendingCreditResponse>> pendingCredits(
      @RequestParam("pending") String pending) {
    if (!"true".equals(pending)) {
      throw new InvalidQueryParameterException("pending");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            refunds.pendingCredits().stream()
                .map(credit -> PendingCreditResponse.of(credit, ids))
                .toList());
  }

  /**
   * A guest id that was never issued is passed as none: the use case answers it as a guest that
   * does not exist, after it checked the rest of the data.
   */
  private @Nullable Long guestOf(String masked) {
    try {
      return ids.unmask(IdKind.GUEST, UUID.fromString(masked));
    } catch (UnknownMaskedIdException forged) {
      return null;
    }
  }
}
