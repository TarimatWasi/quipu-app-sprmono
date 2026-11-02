package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller.PaymentsBffController.QueueItemResponse;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.ChargeView;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.DashboardView;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.ExpiringContractView;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.PendingGuestView;
import com.tarimatwasi.quipu.payment.application.port.in.AdminDashboardUseCase.RejectedPaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HU-A04, CU-04, TAR-236. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class AdminDashboardBffController {

  private final AdminDashboardUseCase dashboard;
  private final IdMasker ids;

  public AdminDashboardBffController(AdminDashboardUseCase dashboard, IdMasker ids) {
    this.dashboard = dashboard;
    this.ids = ids;
  }

  /** DashboardCharge: a row of «Cobros del mes». */
  public record ChargeResponse(
      UUID guestId,
      @Nullable String guestName,
      String documentType,
      String documentNumber,
      GuestKind guestType,
      @Nullable String environmentCode,
      @Nullable UUID contractId,
      @Nullable UUID stayId,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal amount,
      LocalDate dueDate,
      ChargeState status,
      @Nullable UUID paymentId,
      boolean paymentRejected) {
    static ChargeResponse of(ChargeView view, IdMasker ids) {
      return new ChargeResponse(
          ids.mask(IdKind.GUEST, view.guestId()),
          view.guestName(),
          view.documentType(),
          view.documentNumber(),
          view.guestType(),
          view.environmentCode(),
          mask(ids, IdKind.CONTRACT, view.contractId()),
          mask(ids, IdKind.STAY, view.stayId()),
          text(view.period()),
          view.stayStartDate(),
          view.stayEndDate(),
          view.amount(),
          view.dueDate(),
          view.status(),
          mask(ids, IdKind.PAYMENT, view.paymentId()),
          view.paymentRejected());
    }
  }

  /** DashboardChargeRef: an overdue charge named in «Lo que toca ahora». */
  public record ChargeRefResponse(
      UUID guestId,
      @Nullable String guestName,
      @Nullable String environmentCode,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      LocalDate dueDate,
      BigDecimal amount) {
    static ChargeRefResponse of(ChargeView view, IdMasker ids) {
      return new ChargeRefResponse(
          ids.mask(IdKind.GUEST, view.guestId()),
          view.guestName(),
          view.environmentCode(),
          text(view.period()),
          view.stayStartDate(),
          view.stayEndDate(),
          view.dueDate(),
          view.amount());
    }
  }

  /** DashboardOverdue. */
  public record OverdueResponse(long count, BigDecimal total, List<ChargeRefResponse> first) {}

  /** The oldest rejected payment of DashboardRejected. */
  public record RejectedPaymentResponse(
      UUID paymentId,
      @Nullable String guestName,
      @Nullable String period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      Instant rejectedAt) {}

  /** DashboardRejected. */
  public record RejectedResponse(long count, @Nullable RejectedPaymentResponse oldest) {}

  /** DashboardRefunds. */
  public record RefundsResponse(long count, BigDecimal total) {}

  /** The contract that ends first, of DashboardExpiringContracts. */
  public record ExpiringContractResponse(
      UUID contractId,
      @Nullable String guestName,
      @Nullable String environmentCode,
      LocalDate endDate) {}

  /** DashboardExpiringContracts. */
  public record ExpiringResponse(long count, @Nullable ExpiringContractResponse first) {}

  /** The first guest of DashboardGuestsNotActivated. */
  public record PendingGuestResponse(
      UUID guestId,
      String documentType,
      String documentNumber,
      @Nullable Instant accessExpiresAt) {}

  /** DashboardGuestsNotActivated. */
  public record NotActivatedResponse(
      long count, long expired, @Nullable PendingGuestResponse first) {}

  /** The summary of today; the vouchers are rows of the queue, without the amount. */
  public record DashboardResponse(
      int paymentsUpToDate,
      int paymentsUnderReview,
      int paymentsPending,
      int paymentsOverdue,
      List<QueueItemResponse> pendingVouchers,
      long pendingVouchersTotal,
      List<ChargeResponse> charges,
      OverdueResponse overdue,
      RejectedResponse rejected,
      RefundsResponse refunds,
      ExpiringResponse expiringContracts,
      NotActivatedResponse guestsNotActivated) {
    static DashboardResponse of(DashboardView view, IdMasker ids) {
      RejectedPaymentView rejected = view.rejected().oldest();
      ExpiringContractView expiring = view.expiringContracts().first();
      PendingGuestView pending = view.guestsNotActivated().first();
      return new DashboardResponse(
          view.paymentsUpToDate(),
          view.paymentsUnderReview(),
          view.paymentsPending(),
          view.paymentsOverdue(),
          view.pendingVouchers().stream().map(p -> QueueItemResponse.of(p, ids)).toList(),
          view.pendingVouchersTotal(),
          view.charges().stream().map(c -> ChargeResponse.of(c, ids)).toList(),
          new OverdueResponse(
              view.overdue().count(),
              view.overdue().total(),
              view.overdue().first().stream().map(c -> ChargeRefResponse.of(c, ids)).toList()),
          new RejectedResponse(
              view.rejected().count(),
              rejected == null
                  ? null
                  : new RejectedPaymentResponse(
                      ids.mask(IdKind.PAYMENT, rejected.paymentId()),
                      rejected.guestName(),
                      text(rejected.period()),
                      rejected.stayStartDate(),
                      rejected.stayEndDate(),
                      rejected.rejectedAt())),
          new RefundsResponse(view.refunds().count(), view.refunds().total()),
          new ExpiringResponse(
              view.expiringContracts().count(),
              expiring == null
                  ? null
                  : new ExpiringContractResponse(
                      ids.mask(IdKind.CONTRACT, expiring.contractId()),
                      expiring.guestName(),
                      expiring.environmentCode(),
                      expiring.endDate())),
          new NotActivatedResponse(
              view.guestsNotActivated().count(),
              view.guestsNotActivated().expired(),
              pending == null
                  ? null
                  : new PendingGuestResponse(
                      ids.mask(IdKind.GUEST, pending.guestId()),
                      pending.documentType(),
                      pending.documentNumber(),
                      pending.accessExpiresAt())));
    }
  }

  private static @Nullable UUID mask(IdMasker ids, IdKind kind, @Nullable Long id) {
    return id == null ? null : ids.mask(kind, id);
  }

  private static @Nullable String text(@Nullable YearMonth period) {
    return period == null ? null : period.toString();
  }

  @GetMapping("/bff/admin/dashboard")
  public ResponseEntity<DashboardResponse> dashboard() {
    // The figures change with every payment and the rows belong to people: no cache keeps them.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DashboardResponse.of(dashboard.dashboard(), ids));
  }
}
