package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** RF-22: the limit cannot be turned off nor loosened without bound by the environment. */
class AdministratorRateLimitPropertiesTest {

  @Test
  void acceptsFromOneToSixty() {
    assertThat(new AdministratorRateLimitProperties(1).administratorCreationsPerMinute())
        .isEqualTo(1);
    assertThat(new AdministratorRateLimitProperties(60).administratorCreationsPerMinute())
        .isEqualTo(60);
  }

  @Test
  void refusesZeroAndMoreThanSixty() {
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(61))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
