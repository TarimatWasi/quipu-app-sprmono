package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The primary origin of the frontend is required: CORS and the links of the mails use it. */
class FrontendOriginPropertiesTest {

  @Test
  void theOriginIsTheValueConfigured() {
    assertThat(new FrontendOriginProperties("https://quipu-dev.tarimatwasi.com").frontendOrigin())
        .isEqualTo("https://quipu-dev.tarimatwasi.com");
  }

  @Test
  void aBlankOriginIsRefusedAtStartup() {
    assertThatThrownBy(() -> new FrontendOriginProperties(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.cors.allowed-origin");
  }
}
