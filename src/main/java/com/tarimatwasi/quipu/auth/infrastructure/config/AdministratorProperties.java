package com.tarimatwasi.quipu.auth.infrastructure.config;

import com.tarimatwasi.quipu.auth.application.port.out.AdministratorLimitPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RF-22: the limit of administrators.
 *
 * @param max {@code app.administrators.max}: at least 1; counts the ADMIN accounts that are not
 *     inactive
 */
@Validated
@ConfigurationProperties("app.administrators")
public record AdministratorProperties(int max) implements AdministratorLimitPort {

  public AdministratorProperties {
    if (max < 1) {
      throw new IllegalArgumentException("app.administrators.max must be at least 1");
    }
  }

  @Override
  public int maxAdministrators() {
    return max;
  }
}
