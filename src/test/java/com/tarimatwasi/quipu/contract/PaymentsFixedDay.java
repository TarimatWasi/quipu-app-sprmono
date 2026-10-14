package com.tarimatwasi.quipu.contract;

import com.tarimatwasi.quipu.support.MutableClock;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The day of the tests of the payments is fixed at 2026-10-14, 09:00 in Lima. */
@TestConfiguration
class PaymentsFixedDay {

  @Bean
  @Primary
  Clock fixedClock() {
    return new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));
  }
}
