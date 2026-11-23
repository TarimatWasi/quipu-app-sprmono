package com.tarimatwasi.quipu.guest.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountEmailChange;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.EmailChangeOutcome;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.IssuedAccess;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What these records may reach a log with: never the secret, never an email. */
class GuestAccountPortTest {

  @Test
  void anIssuedAccessNeverShowsItsSecret() {
    var access =
        new IssuedAccess(
            AccessMode.LINK,
            "https://quipu.test/activate?code=SECRET",
            Instant.EPOCH,
            "msg SECRET");

    assertThat(access.toString()).contains("LINK").doesNotContain("SECRET");
  }

  @Test
  void anEmailChangeNeverShowsTheEmail() {
    assertThat(new AccountEmailChange(EmailChangeOutcome.CHANGED, "ana@example.com").toString())
        .contains("CHANGED")
        .doesNotContain("ana@example.com");
  }
}
