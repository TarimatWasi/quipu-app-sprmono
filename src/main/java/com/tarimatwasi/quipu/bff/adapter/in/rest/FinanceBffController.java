package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.payment.application.port.in.FinanceSummaryUseCase;
import com.tarimatwasi.quipu.payment.application.port.in.FinanceSummaryUseCase.FinanceSummaryView;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RF-18, CU-08. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class FinanceBffController {

  /** The use case refuses year 0000, which the pattern lets through, as the fines do. */
  private static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

  private final FinanceSummaryUseCase finance;

  public FinanceBffController(FinanceSummaryUseCase finance) {
    this.finance = finance;
  }

  /** The amounts in soles, with two decimals, written as JSON numbers. */
  public record FinanceSummaryResponse(
      String month,
      BigDecimal totalIncome,
      BigDecimal incomeFromPayments,
      BigDecimal incomeFromFines,
      BigDecimal totalExpenses,
      BigDecimal net) {
    static FinanceSummaryResponse of(FinanceSummaryView summary) {
      return new FinanceSummaryResponse(
          summary.month().toString(),
          summary.totalIncome(),
          summary.incomeFromPayments(),
          summary.incomeFromFines(),
          summary.totalExpenses(),
          summary.net());
    }
  }

  /** The figures of the business change with every payment, so no cache may keep them. */
  @GetMapping("/bff/admin/finance")
  public ResponseEntity<FinanceSummaryResponse> summary(@RequestParam("month") String month) {
    if (!MONTH.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(FinanceSummaryResponse.of(finance.summary(YearMonth.parse(month))));
  }
}
