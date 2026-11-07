package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** RF-22: the limit of administrators is at least 1. */
class AdministratorPropertiesTest {

  @Test
  void theLimitIsTheValueConfigured() {
    assertThat(new AdministratorProperties(5).maxAdministrators()).isEqualTo(5);
    assertThat(new AdministratorProperties(1).maxAdministrators()).isEqualTo(1);
  }

  @Test
  void aMaximumBelowOneIsRefusedAtStartup() {
    assertThatThrownBy(() -> new AdministratorProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.administrators.max");
  }
}
