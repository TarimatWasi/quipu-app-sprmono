package com.tarimatwasi.quipu.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** HU-A04: one count per state of the charges of today. */
class PaymentsOverviewTest {

  @Test
  void eachStateCountsInItsOwnFigure() {
    assertThat(
            PaymentsOverview.of(
                List.of(
                    PeriodState.PAID,
                    PeriodState.UNDER_REVIEW,
                    PeriodState.UNDER_REVIEW,
                    PeriodState.UNPAID,
                    PeriodState.UNPAID,
                    PeriodState.UNPAID,
                    PeriodState.OVERDUE,
                    PeriodState.OVERDUE,
                    PeriodState.OVERDUE,
                    PeriodState.OVERDUE)))
        .isEqualTo(new PaymentsOverview(1, 2, 3, 4));
  }

  @Test
  void noChargesAreAllZeros() {
    assertThat(PaymentsOverview.of(List.of())).isEqualTo(new PaymentsOverview(0, 0, 0, 0));
  }
}
