package com.tarimatwasi.quipu.auth.infrastructure.config;

import com.tarimatwasi.quipu.auth.application.port.out.AssetsBaseUrlPort;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * TAR-316: where the public images live (the public bucket of R2 behind its own domain), so that
 * the emblem of the mails does not depend on the frontend deployment. The value is the same in dev
 * and prod, so {@code application.yml} carries it and {@code ASSETS_BASE_URL} only overrides it.
 *
 * @param baseUrl {@code app.assets.base-url}: https, lowercase host, no port, path, query or
 *     trailing slash
 */
@Validated
@ConfigurationProperties("app.assets")
public record AssetsProperties(@DefaultValue("") String baseUrl) implements AssetsBaseUrlPort {

  private static final Pattern HTTPS_HOST = Pattern.compile("https://[a-z0-9-]+(?:\\.[a-z0-9-]+)+");

  public AssetsProperties {
    if (!HTTPS_HOST.matcher(baseUrl).matches()) {
      throw new IllegalArgumentException(
          "app.assets.base-url must be an https address in lowercase without port, path or"
              + " trailing slash, such as https://assets.example.com");
    }
  }

  @Override
  public String assetsBaseUrl() {
    return baseUrl;
  }
}
