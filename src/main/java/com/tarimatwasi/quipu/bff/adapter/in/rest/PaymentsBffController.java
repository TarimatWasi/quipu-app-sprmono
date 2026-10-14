package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentDetailView;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.QueueItemView;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * RF-05, CU-05, CU-14. Only ADMIN reaches /bff/admin/** (RN-08); the reviewer and the author of a
 * direct payment are the ones of the session, never one named in the request.
 */
@RestController
public class PaymentsBffController {

  /** Every part the multipart form of a direct payment defines; any other one is refused. */
  private static final Set<String> FORM_FIELDS =
      Set.of("file", "guestId", "period", "stayId", "amount", "paymentMethod");

  private static final long NO_SUCH_ID = -1L;

  private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private final ManagePaymentsUseCase payments;
  private final IdMasker ids;

  public PaymentsBffController(ManagePaymentsUseCase payments, IdMasker ids) {
    this.payments = payments;
    this.ids = ids;
  }

  /**
   * A payment without the voucher URL. The nullable fields are always in the answer, as an explicit
   * null: the contract requires them.
   */
  public record PaymentResponse(
      UUID id,
      UUID guestId,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      PaymentState status,
      @Nullable String rejectionReason,
      @Nullable Instant reviewedAt,
      Instant createdAt) {
    static PaymentResponse of(PaymentView payment, IdMasker ids) {
      return new PaymentResponse(
          ids.mask(IdKind.PAYMENT, payment.id()),
          ids.mask(IdKind.GUEST, payment.guestId()),
          payment.contractId() == null ? null : ids.mask(IdKind.CONTRACT, payment.contractId()),
          payment.stayId() == null ? null : ids.mask(IdKind.STAY, payment.stayId()),
          payment.period() == null ? null : payment.period().toString(),
          payment.amount(),
          payment.paymentMethod(),
          payment.status(),
          payment.rejectionReason(),
          payment.reviewedAt(),
          payment.createdAt());
    }
  }

  /** The payment with the signed URL of its voucher. */
  public record PaymentDetailResponse(
      UUID id,
      UUID guestId,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      BigDecimal amount,
      @Nullable String paymentMethod,
      PaymentState status,
      @Nullable String rejectionReason,
      @Nullable Instant reviewedAt,
      String voucherUrl,
      Instant createdAt) {
    static PaymentDetailResponse of(PaymentDetailView detail, IdMasker ids) {
      PaymentResponse payment = PaymentResponse.of(detail.payment(), ids);
      return new PaymentDetailResponse(
          payment.id(),
          payment.guestId(),
          payment.contractId(),
          payment.stayId(),
          payment.period(),
          payment.amount(),
          payment.paymentMethod(),
          payment.status(),
          payment.rejectionReason(),
          payment.reviewedAt(),
          detail.voucherUrl(),
          payment.createdAt());
    }
  }

  /** A row of the queue, without the amount. */
  public record QueueItemResponse(
      UUID id,
      UUID guestId,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable String period,
      PaymentState status,
      Instant createdAt) {
    static QueueItemResponse of(QueueItemView item, IdMasker ids) {
      return new QueueItemResponse(
          ids.mask(IdKind.PAYMENT, item.id()),
          ids.mask(IdKind.GUEST, item.guestId()),
          item.guestName(),
          item.documentNumber(),
          item.guestType(),
          item.environmentCode(),
          item.period() == null ? null : item.period().toString(),
          item.status(),
          item.createdAt());
    }
  }

  /** The contract refuses any other field, so an unknown one is a 400 and not ignored. */
  public record ResolveRequest(
      @NotNull Decision status,
      @Nullable String reason,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** The multipart form of a direct payment; the use case checks the ranges and the choice. */
  public record DirectPaymentForm(
      @NotNull MultipartFile file,
      @NotNull UUID guestId,
      @Nullable String period,
      @Nullable UUID stayId,
      @NotNull BigDecimal amount,
      @Nullable String paymentMethod) {}

  @GetMapping("/bff/admin/payments")
  public ResponseEntity<PagedResponse<QueueItemResponse>> list(
      @RequestParam(name = "status", required = false) @Nullable String status,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    // The rows carry the document number of the guests: no cache keeps them.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            PagedResponse.of(
                payments.list(state(status), PageParams.query(after, limit)),
                item -> QueueItemResponse.of(item, ids)));
  }

  /** The state filter is a closed list; any other value is a 400 that names the parameter. */
  private static @Nullable PaymentState state(@Nullable String status) {
    if (status == null) {
      return null;
    }
    try {
      return PaymentState.valueOf(status);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidQueryParameterException("status");
    }
  }

  @GetMapping("/bff/admin/payments/{id}")
  public ResponseEntity<PaymentDetailResponse> get(@PathVariable UUID id) {
    // The body carries a freshly signed URL: no cache may keep it (contract v0.7.x).
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(PaymentDetailResponse.of(payments.get(ids.unmask(IdKind.PAYMENT, id)), ids));
  }

  @PatchMapping("/bff/admin/payments/{id}")
  public PaymentResponse resolve(
      @PathVariable UUID id,
      @Valid @RequestBody ResolveRequest request,
      Authentication authentication) {
    UnknownFieldException.rejectAny(request.unknown());
    return PaymentResponse.of(
        payments.resolve(
            ids.unmask(IdKind.PAYMENT, id),
            new Resolution(
                Long.valueOf(authentication.getName()), request.status(), request.reason())),
        ids);
  }

  @PostMapping(path = "/bff/admin/payments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<PaymentResponse> create(
      @Valid DirectPaymentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    // Spring binds only what the record declares and silently drops the rest, but the form is
    // closed: a misspelled field (periodo) must not register a payment with other terms.
    UnknownFieldException.rejectAnyOutside(
        FORM_FIELDS,
        Stream.concat(
                request.getParameterMap().keySet().stream(), request.getFileMap().keySet().stream())
            .toList());
    String name = form.file().getOriginalFilename();
    PaymentResponse created =
        PaymentResponse.of(
            payments.registerDirect(
                new DirectPayment(
                    Long.valueOf(authentication.getName()),
                    unmaskOrMissing(IdKind.GUEST, form.guestId()),
                    period(form.period()),
                    form.stayId() == null ? null : unmaskOrMissing(IdKind.STAY, form.stayId()),
                    form.amount(),
                    form.paymentMethod(),
                    new VoucherUpload(name == null ? "" : name, content(form.file())))),
            ids);
    return ResponseEntity.created(URI.create("/bff/admin/payments/" + created.id())).body(created);
  }

  /**
   * An id that was never issued answers as the guest or the stay that does not exist, like any
   * other, but only after the fields of the request are checked: a request with a bad amount and a
   * forged id is a 400, not a 404. No payment, guest or stay has the id {@value #NO_SUCH_ID}.
   */
  private Long unmaskOrMissing(IdKind kind, UUID id) {
    try {
      return ids.unmask(kind, id);
    } catch (UnknownMaskedIdException unknown) {
      return NO_SUCH_ID;
    }
  }

  private static @Nullable YearMonth period(@Nullable String period) {
    if (period == null) {
      return null;
    }
    if (!PERIOD.matcher(period).matches()) {
      throw new InvalidPaymentException("period");
    }
    return YearMonth.parse(period);
  }

  private static byte[] content(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException unreadable) {
      throw new InvalidVoucherFileException();
    }
  }
}
