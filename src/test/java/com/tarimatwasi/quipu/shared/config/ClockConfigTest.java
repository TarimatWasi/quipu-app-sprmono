package com.tarimatwasi.quipu.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** QP-SPRMONO-TIM-01: all business time is America/Lima. */
class ClockConfigTest {

  @Test
  void clock_isFixedToAmericaLima() {
    assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneId.of("America/Lima"));
  }

  @Test
  void clock_changesTheCalendarDayAtMidnightInLimaAndNotInUtc() {
    var lima = new ClockConfig().clock().withZone(ZoneId.of("America/Lima"));
    // 2026-11-01 03:00 UTC is still 2026-10-31 22:00 in Lima
    Instant instant = Instant.parse("2026-11-01T03:00:00Z");

    assertThat(LocalDate.ofInstant(instant, lima.getZone()))
        .isEqualTo(LocalDate.parse("2026-10-31"));
  }
}
