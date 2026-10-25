package com.tarimatwasi.quipu.shared.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class R2PropertiesTest {

  @Test
  void toString_doesNotShowTheCredentials() {
    var properties =
        new R2Properties(
            "account",
            "AKIA-VISIBLE-ID",
            "SECRET-VISIBLE",
            "https://r2.example",
            "bucket",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5));

    assertThat(properties.toString())
        .doesNotContain("AKIA-VISIBLE-ID")
        .doesNotContain("SECRET-VISIBLE")
        .contains("bucket", "https://r2.example");
  }

  /** TAR-204: a call to R2 gives up on its own; without a value, 10 s per call and 5 s a try. */
  @Test
  void theTimeoutsHaveADefaultAndReachTheClientConfiguration() {
    var source =
        new MapConfigurationPropertySource(
            Map.of(
                "app.r2.account-id", "a",
                "app.r2.access-key-id", "k",
                "app.r2.secret-access-key", "s",
                "app.r2.endpoint", "https://r2.example",
                "app.r2.bucket-name", "b"));

    R2Properties properties = new Binder(source).bind("app.r2", R2Properties.class).get();

    assertThat(properties.apiCallTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(5));
    var overrides = properties.clientOverrides();
    assertThat(overrides.apiCallTimeout()).contains(Duration.ofSeconds(10));
    assertThat(overrides.apiCallAttemptTimeout()).contains(Duration.ofSeconds(5));
  }
}
