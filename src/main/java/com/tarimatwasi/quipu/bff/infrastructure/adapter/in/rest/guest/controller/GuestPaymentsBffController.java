package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController.GuestProofResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController.PaymentResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController.ReviewResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.ProofParts;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.GuestPaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodPayment;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.StayPayment;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.StayStatusView;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * RF-04, RF-06, CU-16, CU-17, CU-21, CU-22, TAR-239: a guest sends its own payments with their
 * proofs, corrects a rejected one and reads the state of its payments. Only GUEST reaches
 * /bff/guest/** and /bff/temporary-guest/** (RN-08); the guest is always the one of the session,
 * never one named in the request (RN-09), and the use case refuses a guest of the other type. The
 * guest never gets the URL of a voucher nor who reviewed a payment (TAR-230).
 */
@RestController
public class GuestPaymentsBffController {

  /** Every part of the payment form of a guest with a contract (GuestPaymentUpload). */
  private static final Set<String> PERIOD_FORM =
      Set.of("period", ProofParts.PROOFS, ProofParts.FILES);

  /** The form of a temporary guest has no period: the stay is chosen by the server. */
  private static final Set<String> STAY_FORM = Set.of(ProofParts.PROOFS, ProofParts.FILES);

  private final OwnPaymentsUseCase payments;
  private final GuestAccountUseCase accounts;
  private final IdMasker ids;
  private final JsonMapper json;

  public GuestPaymentsBffController(
      OwnPaymentsUseCase payments, GuestAccountUseCase accounts, IdMasker ids, JsonMapper json) {
    this.payments = payments;
    this.accounts = accounts;
    this.ids = ids;
    this.json = json;
  }

  /** One period; the nullable fields are always in the answer, as an explicit null. */
  public record PeriodStatusResponse(
      String period,
      ChargeState status,
      LocalDate dueDate,
      BigDecimal amount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      UUID contractId,
      @Nullable UUID paymentId,
      @Nullable String rejectionReason) {
    public static PeriodStatusResponse of(PeriodView view, IdMasker ids) {
      return new PeriodStatusResponse(
          view.period().toString(),
          view.status(),
          view.dueDate(),
          view.amount(),
          view.creditApplied(),
          view.amountDue(),
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
      @Nullable String rejectionReason,
      @Nullable Object surplus) {
    static StayPaymentStatusResponse of(StayStatusView view, IdMasker ids) {
      return new StayPaymentStatusResponse(
          ids.mask(IdKind.STAY, view.stayId()),
          view.startDate(),
          view.endDate(),
          view.status(),
          view.agreedAmount(),
          view.paymentId() == null ? null : ids.mask(IdKind.PAYMENT, view.paymentId()),
          view.rejectionReason(),
          // RN-38, RN-39: the surplus of a stay arrives with the refunds (TAR-239 part 2).
          null);
    }
  }

  /** GuestPayment of the contract: an own payment without URLs nor who reviewed it. */
  public record GuestPaymentResponse(
      UUID id,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PaymentState status,
      BigDecimal amount,
      @Nullable BigDecimal verifiedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      @Nullable Object surplus,
      @Nullable String rejectionReason,
      Instant submittedAt,
      Instant createdAt,
      List<GuestProofResponse> proofs,
      List<ReviewResponse> reviews) {
    static GuestPaymentResponse of(GuestPaymentView view, IdMasker ids) {
      Long contractId = view.contractId();
      Long stayId = view.stayId();
      YearMonth period = view.period();
      return new GuestPaymentResponse(
          ids.mask(IdKind.PAYMENT, view.id()),
          contractId == null ? null : ids.mask(IdKind.CONTRACT, contractId),
          stayId == null ? null : ids.mask(IdKind.STAY, stayId),
          period == null ? null : period.toString(),
          view.stayStartDate(),
          view.stayEndDate(),
          view.status(),
          view.amount(),
          view.verifiedAmount(),
          view.creditApplied(),
          view.amountDue(),
          // RN-38: the surplus arrives with the credit (TAR-239 part 2).
          null,
          view.rejectionReason(),
          view.submittedAt(),
          view.createdAt(),
          view.proofs().stream().map(p -> GuestProofResponse.of(p, ids)).toList(),
          view.reviews().stream().map(ReviewResponse::of).toList());
    }
  }

  /** GuestHistoryItem: a payment, or (with the refunds, TAR-239 part 2) a refund. */
  public record HistoryItemResponse(
      String kind, @Nullable GuestPaymentResponse payment, @Nullable Object refund) {}

  /** PagedGuestHistory; the credit arrives with TAR-239 part 2, so the balance is still 0. */
  public record GuestHistoryResponse(
      List<HistoryItemResponse> content, @Nullable String nextCursor, BigDecimal creditBalance) {}

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

  /** RF-10, CU-19, H4: the own history of a guest with a contract, without voucher URLs. */
  @GetMapping("/bff/guest/payments")
  public ResponseEntity<GuestHistoryResponse> history(
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after,
      Authentication authentication) {
    var page = PageParams.query(after, limit);
    CursorPage<GuestPaymentView> found = payments.history(guestOf(authentication), page);
    List<HistoryItemResponse> items =
        found.content().stream()
            .map(
                view ->
                    new HistoryItemResponse("PAYMENT", GuestPaymentResponse.of(view, ids), null))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new GuestHistoryResponse(items, found.nextCursor(), BigDecimal.ZERO.setScale(2)));
  }

  /** H3 «Tu pago»: one own payment of a guest with a contract. */
  @GetMapping("/bff/guest/payments/{id}")
  public ResponseEntity<GuestPaymentResponse> payment(
      @PathVariable UUID id, Authentication authentication) {
    return ownPayment(id, authentication, true);
  }

  /** T2 «Tu pago»: the payment of a stay of a temporary guest. */
  @GetMapping("/bff/temporary-guest/payments/{id}")
  public ResponseEntity<GuestPaymentResponse> stayPayment(
      @PathVariable UUID id, Authentication authentication) {
    return ownPayment(id, authentication, false);
  }

  private ResponseEntity<GuestPaymentResponse> ownPayment(
      UUID id, Authentication authentication, boolean contractType) {
    Long guestId = guestOf(authentication);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(GuestPaymentResponse.of(payments.payment(guestId, paymentOf(id), contractType), ids));
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
      MultipartHttpServletRequest request, Authentication authentication) {
    ProofParts.checkParts(PERIOD_FORM, request);
    YearMonth period = PaymentsBffController.period(request.getParameter("period"));
    if (period == null) {
      throw new InvalidPaymentException("period");
    }
    var proofs = ProofParts.uploads(request, json);
    return PaymentResponse.of(
        payments.uploadForPeriod(
            new PeriodPayment(guestOf(authentication), Objects.requireNonNull(period), proofs)),
        ids);
  }

  @PostMapping(
      path = UploadRoutes.TEMPORARY_GUEST_PAYMENTS,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse uploadForStay(
      MultipartHttpServletRequest request, Authentication authentication) {
    ProofParts.checkParts(STAY_FORM, request);
    var proofs = ProofParts.uploads(request, json);
    return PaymentResponse.of(
        payments.uploadForStay(new StayPayment(guestOf(authentication), proofs)), ids);
  }

  /** H3 «Enviar de nuevo» (RN-05): a guest with a contract corrects its rejected payment. */
  @PutMapping(path = UploadRoutes.GUEST_PAYMENT, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PaymentResponse correct(
      @PathVariable UUID id, MultipartHttpServletRequest request, Authentication authentication) {
    return correct(id, request, authentication, true);
  }

  /** T2 «Enviar de nuevo» (RN-05): a temporary guest corrects the payment of its stay. */
  @PutMapping(
      path = UploadRoutes.TEMPORARY_GUEST_PAYMENT,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PaymentResponse correctStay(
      @PathVariable UUID id, MultipartHttpServletRequest request, Authentication authentication) {
    return correct(id, request, authentication, false);
  }

  private PaymentResponse correct(
      UUID id,
      MultipartHttpServletRequest request,
      Authentication authentication,
      boolean contractType) {
    ProofParts.checkParts(PaymentsBffController.CORRECTION_FORM, request);
    var proofs = ProofParts.edits(request, json, ids);
    Long guestId = guestOf(authentication);
    return PaymentResponse.of(payments.correct(guestId, paymentOf(id), contractType, proofs), ids);
  }

  /** RN-09: an id that was never issued is, for the guest, a payment that does not exist. */
  private Long paymentOf(UUID id) {
    try {
      return ids.unmask(IdKind.PAYMENT, id);
    } catch (UnknownMaskedIdException unknown) {
      throw new PaymentNotFoundException();
    }
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
}
