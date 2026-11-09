package com.tarimatwasi.quipu.shared.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** TAR-350: the clients of R2 exist from the start, so the SDK is loaded when the app starts. */
class R2ClientsTest {

  private static R2Properties properties(String endpoint) {
    return new R2Properties(
        "account",
        "test-access-key",
        "test-secret-key",
        endpoint,
        "bucket",
        Duration.ofSeconds(10),
        Duration.ofSeconds(5));
  }

  @Test
  void withCredentialsBothClientsAreBuiltWhenTheBeanIsCreatedAndSharedAfterwards() {
    try (var clients = new R2Clients(properties("http://127.0.0.1:9"))) {
      assertThat(clients.client()).isPresent();
      assertThat(clients.presigner()).isPresent();
      assertThat(clients.client().orElseThrow()).isSameAs(clients.client().orElseThrow());
      assertThat(clients.presigner().orElseThrow()).isSameAs(clients.presigner().orElseThrow());
    }
  }

  @Test
  void withoutCredentialsNothingIsBuiltAndTheApplicationStillStarts() {
    try (var clients = new R2Clients(properties(""))) {
      assertThat(clients.client()).isEmpty();
      assertThat(clients.presigner()).isEmpty();
    }
  }
}
