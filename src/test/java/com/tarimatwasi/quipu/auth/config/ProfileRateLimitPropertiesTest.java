package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** TAR-222: the limit cannot be turned off nor loosened without bound by the environment. */
class ProfileRateLimitPropertiesTest {

  @Test
  void acceptsFromOneToSixty() {
    assertThat(new ProfileRateLimitProperties(1).profileUpdatesPerMinute()).isEqualTo(1);
    assertThat(new ProfileRateLimitProperties(60).profileUpdatesPerMinute()).isEqualTo(60);
  }

  @Test
  void refusesZeroAndMoreThanSixty() {
    assertThatThrownBy(() -> new ProfileRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.rate-limit.profile-updates-per-minute");
    assertThatThrownBy(() -> new ProfileRateLimitProperties(61))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.rate-limit.profile-updates-per-minute");
  }
}
