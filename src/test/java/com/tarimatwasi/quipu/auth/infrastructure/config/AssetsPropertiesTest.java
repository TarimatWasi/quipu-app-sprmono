package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TAR-316: the public images base is an exact https address, or the startup fails. */
class AssetsPropertiesTest {

  @Test
  void theBaseIsTheValueConfigured() {
    assertThat(new AssetsProperties("https://assets.tarimatwasi.com").assetsBaseUrl())
        .isEqualTo("https://assets.tarimatwasi.com");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "http://assets.tarimatwasi.com",
        "https://assets.tarimatwasi.com/",
        "https://assets.tarimatwasi.com/img",
        "https://assets.tarimatwasi.com:8443",
        "https://Assets.tarimatwasi.com",
        "https://user@assets.tarimatwasi.com",
        "https://localhost",
        "assets.tarimatwasi.com"
      })
  void aValueThatIsNotAnExactHttpsAddressIsRefusedAtStartup(String value) {
    assertThatThrownBy(() -> new AssetsProperties(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.assets.base-url");
  }
}
