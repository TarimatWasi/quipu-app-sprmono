package com.tarimatwasi.quipu.auth.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IssuedSessionTest {

  /** A session that reaches a log carries neither of its tokens. */
  @Test
  void itsTextNeverCarriesItsTokens() {
    assertThat(new IssuedSession("ACCESS-SECRET", "REFRESH-SECRET", Duration.ofDays(7)).toString())
        .doesNotContain("ACCESS-SECRET", "REFRESH-SECRET")
        .contains("refreshToken=***");
    assertThat(new IssuedSession("ACCESS-SECRET", null, null).toString())
        .doesNotContain("ACCESS-SECRET")
        .contains("refreshToken=null");
  }
}
