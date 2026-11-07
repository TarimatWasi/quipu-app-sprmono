package com.tarimatwasi.quipu.guest.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** RF-14: the version of the consent in force is required. */
class ConsentPropertiesTest {

  @Test
  void theVersionIsTheValueConfigured() {
    assertThat(new ConsentProperties("2026-11").currentVersion()).isEqualTo("2026-11");
  }

  @Test
  void aBlankVersionIsRefusedAtStartup() {
    assertThatThrownBy(() -> new ConsentProperties(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.consent.version");
  }
}
