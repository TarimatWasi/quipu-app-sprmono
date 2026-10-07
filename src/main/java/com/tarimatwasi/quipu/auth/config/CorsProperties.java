package com.tarimatwasi.quipu.auth.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.cors.CorsConfiguration;

/**
 * Extra origins allowed by CORS besides the primary one ({@code app.cors.allowed-origin}).
 *
 * <p>Behind a rewrite of the hosting layer (TAR-75) the browser keeps the {@code Origin} of the
 * frontend host while the {@code Host} is the backend, so Spring sees a cross-origin request: the
 * hosts of the frontend (its aliases, branch and deployment hosts) must be listed.
 *
 * @param allowedOrigins exact origins ({@code app.cors.allowed-origins})
 * @param allowedOriginPatterns origins with a wildcard inside the first label only ({@code
 *     app.cors.allowed-origin-patterns}); the wildcard matches {@code [a-z0-9-]+}, never a dot
 */
@ConfigurationProperties("app.cors")
@Validated
public record CorsProperties(List<String> allowedOrigins, List<String> allowedOriginPatterns) {

  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    allowedOriginPatterns =
        allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
  }

  /**
   * BE-SPR-SEC-05: validates every origin at startup and builds the CORS policy.
   *
   * @param primaryOrigin the required origin ({@code app.cors.allowed-origin})
   * @param requireHttps true in the deployed profiles (dev and prod)
   */
  public CorsConfiguration configuration(String primaryOrigin, boolean requireHttps) {
    SecurityConfig.validateOrigin(primaryOrigin, requireHttps);
    allowedOrigins.forEach(
        origin -> SecurityConfig.validateOrigin(origin, requireHttps, "app.cors.allowed-origins"));
    allowedOriginPatterns.forEach(SecurityConfig::validateOriginPattern);

    var origins = new ArrayList<String>();
    origins.add(primaryOrigin);
    origins.addAll(allowedOrigins);
    var config = new WildcardCorsConfiguration(allowedOriginPatterns);
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    config.setAllowedHeaders(List.of("Content-Type"));
    // The login counts down from Retry-After (423, TAR-131) and the rate limit uses it too (429):
    // a page on another origin only reads a response header that is exposed.
    config.setExposedHeaders(List.of("Retry-After"));
    config.setAllowCredentials(true);
    return config;
  }

  /**
   * Spring's origin patterns turn {@code *} into {@code .*}, which spans dots and so labels. Here
   * the wildcard is {@code [a-z0-9-]+}; the exact origins keep Spring's semantics. Handler-level
   * {@code @CrossOrigin} would combine this policy into a plain one and lose the patterns: there is
   * none, the policy is global.
   */
  private static final class WildcardCorsConfiguration extends CorsConfiguration {

    private final List<Pattern> patterns;

    WildcardCorsConfiguration(List<String> origins) {
      this.patterns = origins.stream().map(WildcardCorsConfiguration::compile).toList();
    }

    private static Pattern compile(String origin) {
      var parts = origin.split("\\*", -1);
      var regex = new StringBuilder();
      for (int i = 0; i < parts.length; i++) {
        if (i > 0) {
          regex.append("[a-z0-9-]+");
        }
        regex.append(Pattern.quote(parts[i]));
      }
      return Pattern.compile(regex.toString());
    }

    @Override
    public @Nullable String checkOrigin(@Nullable String requestOrigin) {
      var exact = super.checkOrigin(requestOrigin);
      if (exact != null || requestOrigin == null) {
        return exact;
      }
      return patterns.stream().anyMatch(p -> p.matcher(requestOrigin).matches())
          ? requestOrigin
          : null;
    }
  }
}
