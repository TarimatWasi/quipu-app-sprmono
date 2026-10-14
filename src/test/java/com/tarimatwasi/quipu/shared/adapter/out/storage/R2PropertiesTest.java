package com.tarimatwasi.quipu.shared.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class R2PropertiesTest {

  @Test
  void toString_doesNotShowTheCredentials() {
    var properties =
        new R2Properties(
            "account", "AKIA-VISIBLE-ID", "SECRET-VISIBLE", "https://r2.example", "bucket");

    assertThat(properties.toString())
        .doesNotContain("AKIA-VISIBLE-ID")
        .doesNotContain("SECRET-VISIBLE")
        .contains("bucket", "https://r2.example");
  }
}
