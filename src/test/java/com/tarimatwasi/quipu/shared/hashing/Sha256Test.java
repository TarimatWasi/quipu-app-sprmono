package com.tarimatwasi.quipu.shared.hashing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;

class Sha256Test {

  /** The known digest of "abc" (FIPS 180-2), as text and as bytes alike. */
  @Test
  void theDigestOfAbcIsTheKnownOne() {
    String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    assertThat(Sha256.hex("abc")).isEqualTo(expected);
    assertThat(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);
    assertThat(Sha256.digest("abc")).hasSize(32);
  }

  @Test
  void aMissingAlgorithmIsAFailureOfThePlatform() {
    assertThatThrownBy(() -> Sha256.digest(new byte[0], "NOT-AN-ALGORITHM"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT-AN-ALGORITHM")
        .hasCauseInstanceOf(NoSuchAlgorithmException.class);
  }
}
