package com.tarimatwasi.quipu.auth.infrastructure.config;

import com.tarimatwasi.quipu.auth.application.port.out.FrontendOriginPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The primary origin of the frontend: the one CORS always allows and the one the links of the mails
 * (activation, password recovery) are built on. {@link CorsProperties} holds the other origins.
 *
 * @param allowedOrigin {@code app.cors.allowed-origin}: exact origin; https in the deployed
 *     profiles, which {@code SecurityConfig} checks at startup
 */
@Validated
@ConfigurationProperties("app.cors")
public record FrontendOriginProperties(@DefaultValue("") String allowedOrigin)
    implements FrontendOriginPort {

  public FrontendOriginProperties {
    if (allowedOrigin.isBlank()) {
      throw new IllegalArgumentException("app.cors.allowed-origin is required");
    }
  }

  @Override
  public String frontendOrigin() {
    return allowedOrigin;
  }
}
