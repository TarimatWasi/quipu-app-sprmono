package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * RF-22, TAR-127: the limits cannot be turned off nor loosened without bound by the environment.
 */
class AdministratorRateLimitPropertiesTest {

  @Test
  void acceptsFromOneToSixty() {
    var lowest = new AdministratorRateLimitProperties(1, 1, 1);
    assertThat(lowest.administratorCreationsPerMinute()).isEqualTo(1);
    assertThat(lowest.administratorStatusChangesPerMinute()).isEqualTo(1);
    assertThat(lowest.guestAccountEmailChangesPerMinute()).isEqualTo(1);
    var highest = new AdministratorRateLimitProperties(60, 60, 60);
    assertThat(highest.administratorCreationsPerMinute()).isEqualTo(60);
    assertThat(highest.administratorStatusChangesPerMinute()).isEqualTo(60);
    assertThat(highest.guestAccountEmailChangesPerMinute()).isEqualTo(60);
  }

  @Test
  void refusesZeroAndMoreThanSixtyForTheCreations() {
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(0, 3, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrator-creations-per-minute");
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(61, 3, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrator-creations-per-minute");
  }

  @Test
  void refusesZeroAndMoreThanSixtyForTheStatusChanges() {
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(3, 0, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrator-status-changes-per-minute");
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(3, 61, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("administrator-status-changes-per-minute");
  }

  /** TAR-229. */
  @Test
  void refusesZeroAndMoreThanSixtyForTheGuestAccountEmailChanges() {
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(3, 3, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("guest-account-email-changes-per-minute");
    assertThatThrownBy(() -> new AdministratorRateLimitProperties(3, 3, 61))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("guest-account-email-changes-per-minute");
  }
}
