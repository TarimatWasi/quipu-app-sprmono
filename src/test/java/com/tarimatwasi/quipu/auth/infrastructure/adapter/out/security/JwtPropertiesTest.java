package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

  /** A token that expires at once, or never set, would log everybody out: it fails at startup. */
  @Test
  void theExpirationIsRequiredAndPositive() {
    for (Duration wrong : new Duration[] {null, Duration.ZERO, Duration.ofMinutes(-1)}) {
      assertThatThrownBy(() -> new JwtProperties("secret", wrong))
          .as(String.valueOf(wrong))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new JwtProperties("secret", Duration.ofMinutes(15)).expiration())
        .isEqualTo(Duration.ofMinutes(15));
  }
}
