package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** TAR-221: the limit cannot be turned off nor loosened without bound by the environment. */
class AdminAreaRateLimitPropertiesTest {

  @Test
  void acceptsFromOneToSixHundred() {
    assertThat(new AdminAreaRateLimitProperties(1).adminRequestsPerMinute()).isEqualTo(1);
    assertThat(new AdminAreaRateLimitProperties(600).adminRequestsPerMinute()).isEqualTo(600);
  }

  @Test
  void refusesZeroAndMoreThanSixHundred() {
    assertThatThrownBy(() -> new AdminAreaRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.rate-limit.admin-requests-per-minute");
    assertThatThrownBy(() -> new AdminAreaRateLimitProperties(601))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.rate-limit.admin-requests-per-minute");
  }
}
