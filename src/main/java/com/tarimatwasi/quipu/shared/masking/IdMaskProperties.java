package com.tarimatwasi.quipu.shared.masking;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key that masks the ids the BFF shows ({@code app.id-mask.*}).
 *
 * @param key {@code app.id-mask.key}: from the environment, never from a file, at least 32
 *     characters. Changing it changes every masked id: links saved by a client stop working.
 */
@ConfigurationProperties("app.id-mask")
@Validated
public record IdMaskProperties(@NotBlank String key) {

  private static final int MIN_KEY_LENGTH = 32;

  /** A short key is easy to guess: fail at startup. */
  public IdMaskProperties {
    if (key != null && key.length() < MIN_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "app.id-mask.key must have at least " + MIN_KEY_LENGTH + " characters");
    }
  }
}
