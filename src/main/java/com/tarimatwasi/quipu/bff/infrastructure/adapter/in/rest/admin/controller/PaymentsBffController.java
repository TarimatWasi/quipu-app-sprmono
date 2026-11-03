package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.UnknownFieldException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.PagedResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestPaymentsBffController.PeriodStatusResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller.GuestPaymentsBffController.SurplusResponse;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PayablePeriodView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PayableState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentDetailView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofEdit;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofFileView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofSource;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.QueueItemView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ReviewView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ReviewerView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.UnknownMaskedIdException;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * RF-05, CU-05, CU-14, TAR-239. Only ADMIN reaches /bff/admin/** (RN-08); the reviewer and the
 * author of a direct payment or a correction are the ones of the session, never one named in the
 * request.
 */
@RestController
public class PaymentsBffController {

  /** Every part the multipart form of a direct payment defines; any other one is refused. */
  private static final Set<String> DIRECT_FORM =
      Set.of("guestId", "period", "stayId", "receivedOn", ProofParts.PROOFS, ProofParts.FILES);

  /** Every part of a correction of the proofs (ProofsCorrection). */
  public static final Set<String> CORRECTION_FORM = Set.of(ProofParts.PROOFS, ProofParts.FILES);

  private static final long NO_SUCH_ID = -1L;

  private static final Pattern PERIOD = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private final ManagePaymentsUseCase payments;
  private final IdMasker ids;
  private final JsonMapper json;

  public PaymentsBffController(ManagePaymentsUseCase payments, IdMasker ids, JsonMapper json) {
    this.payments = payments;
    this.ids = ids;
    this.json = json;
  }

  /**
   * PaymentSummary: a payment without its proofs' URLs. The nullable fields are always in the
   * answer, as an explicit null: the contract requires them.
   */
  public record PaymentResponse(
      UUID id,
      UUID guestId,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      BigDecimal amount,
      @Nullable BigDecimal verifiedAmount,
      @Nullable String paymentMethod,
      PaymentState status,
      @Nullable String rejectionReason,
      int proofCount,
      int voucherCount,
      boolean hasCash,
      @Nullable Instant reviewedAt,
      Instant submittedAt,
      Instant createdAt) {
    public static PaymentResponse of(PaymentView payment, IdMasker ids) {
      return new PaymentResponse(
          ids.mask(IdKind.PAYMENT, payment.id()),
          ids.mask(IdKind.GUEST, payment.guestId()),
          payment.contractId() == null ? null : ids.mask(IdKind.CONTRACT, payment.contractId()),
          payment.stayId() == null ? null : ids.mask(IdKind.STAY, payment.stayId()),
          payment.period() == null ? null : payment.period().toString(),
          payment.amount(),
          payment.verifiedAmount(),
          payment.paymentMethod(),
          payment.status(),
          payment.rejectionReason(),
          payment.proofCount(),
          payment.voucherCount(),
          payment.hasCash(),
          payment.reviewedAt(),
          payment.submittedAt(),
          payment.createdAt());
    }
  }

  /** ProofFileInfo of the contract. */
  public record ProofFileResponse(String name, String contentType, long sizeBytes) {
    static @Nullable ProofFileResponse of(@Nullable ProofFileView file) {
      return file == null
          ? null
          : new ProofFileResponse(file.name(), file.contentType(), file.sizeBytes());
    }
  }

  /** GuestProof of the contract: a proof without its URL. */
  public record GuestProofResponse(
      UUID id,
      ProofType kind,
      @Nullable String method,
      BigDecimal declaredAmount,
      @Nullable BigDecimal verifiedAmount,
      ProofSource uploadedBy,
      Instant addedAt,
      @Nullable LocalDate receivedOn,
      @Nullable ProofFileResponse file) {
    public static GuestProofResponse of(ProofView proof, IdMasker ids) {
      return new GuestProofResponse(
          ids.mask(IdKind.PAYMENT_PROOF, proof.id()),
          proof.kind(),
          proof.method(),
          proof.declaredAmount(),
          proof.verifiedAmount(),
          proof.uploadedBy(),
          proof.addedAt(),
          proof.receivedOn(),
          ProofFileResponse.of(proof.file()));
    }
  }

  /** AdminProof of the contract: with the signed URL of its voucher. */
  public record AdminProofResponse(
      UUID id,
      ProofType kind,
      @Nullable String method,
      BigDecimal declaredAmount,
      @Nullable BigDecimal verifiedAmount,
      ProofSource uploadedBy,
      Instant addedAt,
      @Nullable LocalDate receivedOn,
      @Nullable ProofFileResponse file,
      @Nullable String fileUrl) {
    static AdminProofResponse of(ProofView proof, IdMasker ids) {
      return new AdminProofResponse(
          ids.mask(IdKind.PAYMENT_PROOF, proof.id()),
          proof.kind(),
          proof.method(),
          proof.declaredAmount(),
          proof.verifiedAmount(),
          proof.uploadedBy(),
          proof.addedAt(),
          proof.receivedOn(),
          ProofFileResponse.of(proof.file()),
          proof.fileUrl());
    }
  }

  /** PaymentReview of the contract. */
  public record ReviewResponse(Decision decision, @Nullable String reason, Instant at) {
    public static ReviewResponse of(ReviewView review) {
      return new ReviewResponse(review.decision(), review.reason(), review.at());
    }
  }

  /** ReviewerRef of the contract (TAR-230). */
  public record ReviewerResponse(UUID id, String name) {
    static @Nullable ReviewerResponse of(@Nullable ReviewerView reviewer, IdMasker ids) {
      return reviewer == null
          ? null
          : new ReviewerResponse(ids.mask(IdKind.USER, reviewer.id()), reviewer.name());
    }
  }

  /** Payment of the contract: what is paid, its proofs with their URLs and its decisions. */
  public record PaymentDetailResponse(
      UUID id,
      UUID guestId,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal expectedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean lastPeriod,
      BigDecimal amount,
      @Nullable BigDecimal verifiedAmount,
      PaymentState status,
      @Nullable String rejectionReason,
      @Nullable SurplusResponse surplus,
      @Nullable Instant reviewedAt,
      @Nullable ReviewerResponse reviewedBy,
      Instant submittedAt,
      Instant createdAt,
      List<AdminProofResponse> proofs,
      List<ReviewResponse> reviews) {
    static PaymentDetailResponse of(PaymentDetailView detail, IdMasker ids) {
      PaymentResponse payment = PaymentResponse.of(detail.payment(), ids);
      return new PaymentDetailResponse(
          payment.id(),
          payment.guestId(),
          detail.guestName(),
          detail.documentNumber(),
          detail.guestType(),
          detail.environmentCode(),
          payment.contractId(),
          payment.stayId(),
          payment.period(),
          detail.stayStartDate(),
          detail.stayEndDate(),
          detail.expectedAmount(),
          detail.creditApplied(),
          detail.amountDue(),
          detail.lastPeriod(),
          payment.amount(),
          payment.verifiedAmount(),
          payment.status(),
          payment.rejectionReason(),
          SurplusResponse.of(detail.surplus()),
          payment.reviewedAt(),
          ReviewerResponse.of(detail.reviewedBy(), ids),
          payment.submittedAt(),
          payment.createdAt(),
          detail.proofs().stream().map(p -> AdminProofResponse.of(p, ids)).toList(),
          detail.reviews().stream().map(ReviewResponse::of).toList());
    }
  }

  /** PaymentQueueItem: a row of the queue, without the amount. */
  public record QueueItemResponse(
      UUID id,
      UUID guestId,
      @Nullable String guestName,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PaymentState status,
      int proofCount,
      int voucherCount,
      boolean hasCash,
      @Nullable ReviewerResponse reviewedBy,
      Instant submittedAt,
      Instant createdAt) {
    public static QueueItemResponse of(QueueItemView item, IdMasker ids) {
      return new QueueItemResponse(
          ids.mask(IdKind.PAYMENT, item.id()),
          ids.mask(IdKind.GUEST, item.guestId()),
          item.guestName(),
          item.documentNumber(),
          item.guestType(),
          item.environmentCode(),
          item.period() == null ? null : item.period().toString(),
          item.stayStartDate(),
          item.stayEndDate(),
          item.status(),
          item.proofCount(),
          item.voucherCount(),
          item.hasCash(),
          ReviewerResponse.of(item.reviewedBy(), ids),
          item.submittedAt(),
          item.createdAt());
    }
  }

  /** What was verified of one proof (RN-16). */
  public record VerifiedProof(
      @NotNull UUID id,
      @NotNull BigDecimal verifiedAmount,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** The contract refuses any other field, so an unknown one is a 400 and not ignored. */
  public record ResolveRequest(
      @NotNull Decision status,
      @Nullable String reason,
      @Nullable List<@Valid VerifiedProof> proofs,
      @JsonAnySetter @Nullable Map<String, Object> unknown) {}

  /** The text fields of a direct payment; the use case checks the ranges and the choice. */
  public record DirectPaymentForm(
      @NotNull UUID guestId,
      @Nullable String period,
      @Nullable UUID stayId,
      @Nullable String receivedOn) {}

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

  /**
   * RF-10: the history of a guest of either type. The parameters are checked before the id is
   * unmasked, and an id that was never issued reads as a guest that does not exist only after the
   * cursor is checked: a forged cursor is a 400 for any id.
   */
  @GetMapping("/bff/admin/guests/{id}/payments")
  public ResponseEntity<PagedResponse<PaymentResponse>> history(
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    var page = PageParams.query(after, limit);
    // The payments of a person: no cache keeps them.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            PagedResponse.of(
                payments.history(unmaskOrMissing(IdKind.GUEST, id), page),
                payment -> PaymentResponse.of(payment, ids)));
  }

  /** PayablePeriod (A5.1): a period or a stay the guest can pay or has pending. */
  public record PayablePeriodResponse(
      @Nullable UUID contractId,
      @Nullable String period,
      @Nullable UUID stayId,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      PayableState status,
      LocalDate dueDate,
      BigDecimal expectedAmount,
      BigDecimal creditApplied,
      BigDecimal amountDue,
      boolean lastPeriod,
      @Nullable UUID paymentId) {
    static PayablePeriodResponse of(PayablePeriodView view, IdMasker ids) {
      Long contractId = view.contractId();
      Long stayId = view.stayId();
      Long paymentId = view.paymentId();
      YearMonth period = view.period();
      return new PayablePeriodResponse(
          contractId == null ? null : ids.mask(IdKind.CONTRACT, contractId),
          period == null ? null : period.toString(),
          stayId == null ? null : ids.mask(IdKind.STAY, stayId),
          view.stayStartDate(),
          view.stayEndDate(),
          view.status(),
          view.dueDate(),
          view.expectedAmount(),
          view.creditApplied(),
          view.amountDue(),
          view.lastPeriod(),
          paymentId == null ? null : ids.mask(IdKind.PAYMENT, paymentId));
    }
  }

  /** A5.1 (TAR-240): what the guest can pay or has pending, for a direct payment. */
  @GetMapping("/bff/admin/guests/{id}/payable-periods")
  public ResponseEntity<List<PayablePeriodResponse>> payablePeriods(@PathVariable UUID id) {
    // What a person owes: no cache keeps it.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            payments.payablePeriods(unmaskOrMissing(IdKind.GUEST, id)).stream()
                .map(view -> PayablePeriodResponse.of(view, ids))
                .toList());
  }

  /** RF-06: the status by period of a guest of either type, as the guest sees it. */
  @GetMapping("/bff/admin/guests/{id}/account-status")
  public ResponseEntity<List<PeriodStatusResponse>> accountStatus(@PathVariable UUID id) {
    // The state of the payments of a person: no cache keeps it.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            payments.accountStatus(unmaskOrMissing(IdKind.GUEST, id)).stream()
                .map(view -> PeriodStatusResponse.of(view, ids))
                .toList());
  }

  /**
   * RF-06: the status by period of the current contract of an environment, active or not. An id
   * that was never issued reads as the environment that does not exist, as in getEnvironment.
   */
  @GetMapping("/bff/admin/environments/{id}/account-status")
  public ResponseEntity<List<PeriodStatusResponse>> environmentAccountStatus(
      @PathVariable UUID id) {
    // The state of the payments of the guest who holds it: no cache keeps it.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            payments.environmentAccountStatus(ids.unmask(IdKind.ENVIRONMENT, id)).stream()
                .map(view -> PeriodStatusResponse.of(view, ids))
                .toList());
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
    // The body carries freshly signed URLs: no cache may keep it (contract v0.7.x).
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
    Map<Long, BigDecimal> verified = new HashMap<>();
    List<VerifiedProof> proofs = request.proofs();
    if (proofs != null) {
      if (proofs.isEmpty() || proofs.size() > ManagePaymentsUseCase.MAX_PROOFS) {
        throw new InvalidPaymentException("proofs");
      }
      for (VerifiedProof proof : proofs) {
        UnknownFieldException.rejectAny(proof.unknown());
        try {
          if (verified.put(ids.unmask(IdKind.PAYMENT_PROOF, proof.id()), proof.verifiedAmount())
              != null) {
            throw new InvalidPaymentException("proofs");
          }
        } catch (UnknownMaskedIdException unknown) {
          throw new InvalidPaymentException("proofs");
        }
      }
    }
    return PaymentResponse.of(
        payments.resolve(
            ids.unmask(IdKind.PAYMENT, id),
            new Resolution(
                Long.valueOf(authentication.getName()),
                request.status(),
                request.reason(),
                verified)),
        ids);
  }

  /** A5.2, TAR-239: the administrator adds or corrects the proofs; the status does not change. */
  @PutMapping(
      path = UploadRoutes.ADMIN_PAYMENT_PROOFS,
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PaymentResponse correct(
      @PathVariable UUID id, MultipartHttpServletRequest request, Authentication authentication) {
    ProofParts.checkParts(CORRECTION_FORM, request);
    List<ProofEdit> proofs = ProofParts.edits(request, json, ids);
    return PaymentResponse.of(
        payments.correct(
            Long.valueOf(authentication.getName()), ids.unmask(IdKind.PAYMENT, id), proofs),
        ids);
  }

  @PostMapping(path = UploadRoutes.ADMIN_PAYMENTS, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<PaymentResponse> create(
      @Valid DirectPaymentForm form,
      MultipartHttpServletRequest request,
      Authentication authentication) {
    ProofParts.checkParts(DIRECT_FORM, request);
    YearMonth period = period(form.period());
    LocalDate receivedOn = receivedOn(form.receivedOn());
    List<ProofUpload> proofs = ProofParts.uploads(request, json);
    PaymentResponse created =
        PaymentResponse.of(
            payments.registerDirect(
                new DirectPayment(
                    Long.valueOf(authentication.getName()),
                    unmaskOrMissing(IdKind.GUEST, form.guestId()),
                    period,
                    form.stayId() == null ? null : unmaskOrMissing(IdKind.STAY, form.stayId()),
                    receivedOn,
                    proofs)),
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

  public static @Nullable YearMonth period(@Nullable String period) {
    if (period == null) {
      return null;
    }
    if (!PERIOD.matcher(period).matches()) {
      throw new InvalidPaymentException("period");
    }
    return YearMonth.parse(period);
  }

  private static @Nullable LocalDate receivedOn(@Nullable String receivedOn) {
    if (receivedOn == null) {
      return null;
    }
    try {
      return LocalDate.parse(receivedOn);
    } catch (DateTimeParseException malformed) {
      throw new InvalidPaymentException("receivedOn");
    }
  }
}
