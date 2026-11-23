package com.tarimatwasi.quipu.payment.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentOverdueTest {

  private static final EventMetadata META =
      new EventMetadata(UUID.randomUUID(), Instant.EPOCH, null);

  /** The audit trail files it under what it charges: the contract, or else the stay. */
  @Test
  void itsSubjectIsItsContractOrElseItsStay() {
    LocalDate day = LocalDate.of(2026, 10, 5);
    assertThat(
            new PaymentOverdue(META, 5L, 7L, null, YearMonth.of(2026, 10), BigDecimal.TEN, day)
                .subject())
        .isEqualTo("contract/7");
    assertThat(new PaymentOverdue(META, 5L, null, 8L, null, BigDecimal.TEN, day).subject())
        .isEqualTo("stay/8");
  }
}
