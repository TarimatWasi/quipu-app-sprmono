package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.adapter.in.rest.PaymentsBffController.PaymentResponse;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.PeriodVoucher;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.StayStatusView;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase.StayVoucher;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * RF-04, RF-06, CU-16, CU-17, CU-21, CU-22: a guest uploads its own vouchers and reads the state of
 * its payments. Only GUEST reaches /bff/guest/** and /bff/temporary-guest/** (RN-08); the guest is
 * always the one of the session, never one named in the request (RN-09), and the use case refuses a
 * guest of the other type. The guest never gets the URL of a voucher.
 */
@RestController
public class GuestPaymentsBffController {

  /** Every part the voucher form of a guest with a contract defines; any other one is refused. */
  private static final Set<String> PERIOD_FORM =
      Set.of("file", "period", "amount", "paymentMethod");

  /** The form of a temporary guest has no period: the stay is chosen by the server. */
  private static final Set<String> STAY_FORM = Set.of("file", "amount", "paymentMethod");

  private final OwnPaymentsUseCase payments;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;

  public GuestPaymentsBffController(
      OwnPaymentsUseCase payments, GuestAccountUseCase accounts, IdMasker ids) {
    this.payments = payments;
    this.accounts = accounts;
    this.ids = ids;
  }

  /** One period; the nullable fields are always in the answer, as an explicit null. */
  public record PeriodStatusResponse(
      String period,
      ChargeState status,
      LocalDate dueDate,
      BigDecimal amount,
      UUID contractId,
      @Nullable UUID paymentId,
      @Nullable String rejectionReason) {
    static PeriodStatusResponse of(PeriodView view, IdMasker ids) {
      return new PeriodStatusResponse(
          view.period().toString(),
          view.status(),
          view.dueDate(),
          view.amount(),
          ids.mask(IdKind.CONTRACT, view.contractId()),
          view.paymentId() == null ? null : ids.mask(IdKind.PAYMENT, view.paymentId()),
          view.rejectionReason());
    }
  }

  /** The stay with the state of its payment; the nullable fields are always in the answer. */
  public record StayPaymentStatusResponse(
      UUID stayId,
      LocalDate startDate,
      LocalDate endDate,
      ChargeState status,
      BigDecimal agreedAmount,
      @Nullable UUID paymentId,
      @Nullable String rejectionReason) {
    static StayPaymentStatusResponse of(StayStatusView view, IdMasker ids) {
      return new StayPaymentStatusResponse(
          ids.mask(IdKind.STAY, view.stayId()),
          view.startDate(),
          view.endDate(),
          view.status(),
          view.agreedAmount(),
          view.paymentId() == null ? null : ids.mask(IdKind.PAYMENT, view.paymentId()),
          view.rejectionReason());
    }
  }

  /** The voucher form of a guest with a contract; the use case checks the ranges. */
  public record PeriodVoucherForm(
      @NotNull MultipartFile file,
      @NotNull String period,
      @NotNull BigDecimal amount,
      @Nullable String paymentMethod) {}

  /** The voucher form of a temporary guest. */
  public record StayVoucherForm(
      @NotNull MultipartFile file, @NotNull BigDecimal amount, @Nullable String paymentMethod) {}

  @GetMapping("/bff/guest/account-status")
  public ResponseEntity<List<PeriodStatusResponse>> accountStatus(Authentication authentication) {
    // The state of the payments of a person: no cache keeps it.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            payments.accountStatus(guestOf(authentication)).stream()
                .map(view -> PeriodStatusResponse.of(view, ids))
                .toList());
  }

  /** RF-10, CU-19: the own history of a guest with a contract, without the voucher URLs. */
  @GetMapping("/bff/guest/payments")
  public ResponseEntity<PagedResponse<PaymentResponse>> history(
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after,
      Authentication authentication) {
    var page = PageParams.query(after, limit);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            PagedResponse.of(
                payments.history(guestOf(authentication), page),
                payment -> PaymentResponse.of(payment, ids)));
  }

  @GetMapping("/bff/temporary-guest/payment-status")
  public ResponseEntity<StayPaymentStatusResponse> stayStatus(Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(StayPaymentStatusResponse.of(payments.stayStatus(guestOf(authentication)), ids));
  }

  @PostMapping(path = UploadRoutes.GUEST_PAYMENTS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse uploadForPeriod(
      @Valid PeriodVoucherForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    PaymentsBffController.checkVoucherForm(PERIOD_FORM, request);
    YearMonth period = PaymentsBffController.period(form.period());
    return PaymentResponse.of(
        payments.uploadForPeriod(
            new PeriodVoucher(
                guestOf(authentication),
                Objects.requireNonNull(period),
                form.amount(),
                form.paymentMethod(),
                upload(form.file()))),
        ids);
  }

  @PostMapping(
      path = UploadRoutes.TEMPORARY_GUEST_PAYMENTS,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse uploadForStay(
      @Valid StayVoucherForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    PaymentsBffController.checkVoucherForm(STAY_FORM, request);
    return PaymentResponse.of(
        payments.uploadForStay(
            new StayVoucher(
                guestOf(authentication), form.amount(), form.paymentMethod(), upload(form.file()))),
        ids);
  }

  /**
   * The guest of the session (RN-09). A GUEST account always belongs to a guest; one that does not
   * is refused like a guest of the other type.
   */
  private Long guestOf(Authentication authentication) {
    return accounts
        .guestOf(Long.valueOf(authentication.getName()))
        .orElseThrow(OtherGuestTypeException::new);
  }

  private static VoucherUpload upload(MultipartFile file) {
    String name = file.getOriginalFilename();
    return new VoucherUpload(name == null ? "" : name, PaymentsBffController.content(file));
  }
}
