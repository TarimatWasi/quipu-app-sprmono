package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SessionJwtTest {

  /** HS256 needs a key of 256 bits: a shorter secret fails at startup, never signs. */
  @Test
  void aSecretShorterThanTheKeyOfHs256IsRefused() {
    assertThatThrownBy(() -> SessionJwt.key("short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32 bytes");
    assertThat(SessionJwt.key("a".repeat(32)).getAlgorithm()).isEqualTo("HmacSHA256");
  }
}
