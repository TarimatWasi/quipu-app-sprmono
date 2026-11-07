package com.tarimatwasi.quipu.guest.infrastructure.config;

import com.tarimatwasi.quipu.guest.application.port.out.ConsentVersionPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RF-14: the consent text in force.
 *
 * @param version {@code app.consent.version}: its text is the resource {@code
 *     consent/<version>.txt}
 */
@Validated
@ConfigurationProperties("app.consent")
public record ConsentProperties(@DefaultValue("") String version) implements ConsentVersionPort {

  public ConsentProperties {
    if (version.isBlank()) {
      throw new IllegalArgumentException("app.consent.version is required");
    }
  }

  @Override
  public String currentVersion() {
    return version;
  }
}
