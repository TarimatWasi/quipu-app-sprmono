package com.tarimatwasi.quipu.auth.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase.IssuedAccess;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GuestAccessUseCaseTest {

  /** An access that reaches a log never carries its URL or its temporary password. */
  @Test
  void anIssuedAccessNeverShowsItsSecret() {
    var access =
        new IssuedAccess(AccessMode.TEMPORARY_PASSWORD, "Pass-SECRET", Instant.EPOCH, "SECRET");

    assertThat(access.toString()).contains("TEMPORARY_PASSWORD").doesNotContain("SECRET");
  }
}
