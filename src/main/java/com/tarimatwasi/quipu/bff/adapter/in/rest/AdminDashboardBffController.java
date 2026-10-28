package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.bff.adapter.in.rest.PaymentsBffController.PaymentResponse;
import com.tarimatwasi.quipu.payment.port.in.AdminDashboardUseCase;
import com.tarimatwasi.quipu.payment.port.in.AdminDashboardUseCase.DashboardView;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HU-A04, CU-04. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class AdminDashboardBffController {

  private final AdminDashboardUseCase dashboard;
  private final IdMasker ids;

  public AdminDashboardBffController(AdminDashboardUseCase dashboard, IdMasker ids) {
    this.dashboard = dashboard;
    this.ids = ids;
  }

  /** The summary of today; the vouchers are PaymentSummary, without their URL. */
  public record DashboardResponse(
      int paymentsUpToDate,
      int paymentsUnderReview,
      int paymentsPending,
      int paymentsOverdue,
      List<PaymentResponse> pendingVouchers,
      long pendingVouchersTotal) {
    static DashboardResponse of(DashboardView view, IdMasker ids) {
      return new DashboardResponse(
          view.paymentsUpToDate(),
          view.paymentsUnderReview(),
          view.paymentsPending(),
          view.paymentsOverdue(),
          view.pendingVouchers().stream().map(p -> PaymentResponse.of(p, ids)).toList(),
          view.pendingVouchersTotal());
    }
  }

  @GetMapping("/bff/admin/dashboard")
  public ResponseEntity<DashboardResponse> dashboard() {
    // The figures change with every payment and the vouchers belong to people: no cache keeps them.
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DashboardResponse.of(dashboard.dashboard(), ids));
  }
}
