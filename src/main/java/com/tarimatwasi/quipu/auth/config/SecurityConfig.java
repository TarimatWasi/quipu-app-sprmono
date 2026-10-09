package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.auth.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.auth.adapter.out.security.CookieBearerTokenResolver;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** HTTP security baseline (BE-SPR-SEC-01, SEC-04, SEC-05, SEC-09). */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  /** Endpoints that need no session; a stale session cookie is not even read there. */
  private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    "/bff/auth/login",
    "/bff/auth/forgot-password",
    "/bff/auth/reset-password",
    "/error"
  };

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      AccountJwtAuthenticationConverter sessionConverter,
      RateLimitProperties rateLimit)
      throws Exception {
    // CSRF is disabled on purpose (ADR-006). Mitigation, in layers: the session cookie is
    // SameSite=Lax (the browser talks to the frontend origin and the hosting layer rewrites
    // /bff, TAR-75); JsonOnlyFilter rejects any request with a body that is not JSON (415);
    // bodyless requests pass it and rely on CORS rejecting a foreign Origin (an allow-list of
    // exact origins and anchored patterns, see CorsProperties).
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults()) // uses the corsConfigurationSource bean
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(new NoSessionEntryPoint())
                    .accessDeniedHandler(new ForbiddenHandler()))
        // The session is Spring Security's resource server (TAR-164): the token comes from the
        // cookie, the decoder checks signature and dates, and the converter applies the rules of
        // the account (TAR-125). An invalid token answers AUTH_NO_SESSION like a missing one.
        .oauth2ResourceServer(
            o ->
                o.bearerTokenResolver(new CookieBearerTokenResolver(publicRequests()))
                    .authenticationEntryPoint(new NoSessionEntryPoint())
                    .jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(sessionConverter)))
        .authorizeHttpRequests(
            a ->
                a
                    // RF-12: a session pending a password change can only change it.
                    .requestMatchers(new PasswordChangePendingMatcher())
                    .denyAll()
                    .requestMatchers(PUBLIC_PATHS)
                    .permitAll()
                    .requestMatchers("/bff/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        // Both run before the session is read, so a flood of bad requests costs no account lookup.
        .addFilterBefore(new RateLimitFilter(rateLimit), BearerTokenAuthenticationFilter.class)
        .addFilterBefore(new JsonOnlyFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static RequestMatcher publicRequests() {
    var paths = PathPatternRequestMatcher.withDefaults();
    return new OrRequestMatcher(
        Arrays.stream(PUBLIC_PATHS).map(paths::matcher).toArray(RequestMatcher[]::new));
  }

  /**
   * The primary origin is required; {@link CorsProperties} adds the others. The deployed profiles
   * (dev and prod) accept https origins only.
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${app.cors.allowed-origin}") String allowedOrigin,
      CorsProperties cors,
      Environment environment) {
    boolean deployed = environment.acceptsProfiles(Profiles.of("dev", "prod"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors.configuration(allowedOrigin, deployed));
    return source;
  }

  static void validateOrigin(String origin) {
    validateOrigin(origin, false);
  }

  static void validateOrigin(String origin, boolean requireHttps) {
    validateOrigin(origin, requireHttps, "app.cors.allowed-origin");
  }

  /**
   * Lowercase labels of letters, digits and hyphens: no userinfo, query, fragment or dot at the
   * end.
   */
  private static final String HOST = "[a-z0-9-]+(?:\\.[a-z0-9-]+)*";

  /**
   * BE-SPR-SEC-05: an exact origin, so strict that a typo fails the startup instead of silently
   * never matching: lowercase, no userinfo, path, query, fragment or trailing dot. The deployed
   * profiles (dev and prod) also require https and no port; local allows {@code http} and a port.
   */
  static void validateOrigin(String origin, boolean requireHttps, String property) {
    var pattern = requireHttps ? "https://" + HOST : "https?://" + HOST + "(?::\\d{1,5})?";
    if (!origin.matches(pattern)) {
      throw new IllegalStateException(
          property
              + " must be an exact origin in lowercase such as https://app.example.com"
              + (requireHttps ? " (https, no port" : " (no")
              + ", wildcard, path, userinfo or trailing dot), got: "
              + origin);
    }
  }

  /**
   * BE-SPR-SEC-05: a pattern is https and lowercase, has no userinfo, port, path or trailing dot,
   * and allows the wildcard only inside the first DNS label ({@code
   * https://app-*-team.example.com}) after a literal prefix of 8 or more characters. The literal
   * tail after the last wildcard has at least 20 characters, so that it carries the suffix bound to
   * the team ({@code -team.example.com}) and a bare {@code https://app-*.vercel.app} is refused.
   * {@link CorsProperties} matches the wildcard as {@code [a-z0-9-]+}: it never spans a dot.
   */
  static void validateOriginPattern(String pattern) {
    boolean wellFormed = pattern.matches("https://[a-z0-9*-]+(?:\\.[a-z0-9-]+)+");
    int firstWildcard = pattern.indexOf('*');
    int lastWildcard = pattern.lastIndexOf('*');
    boolean safe =
        wellFormed
            && firstWildcard >= "https://".length() + 8
            && !pattern.contains("**")
            && pattern.length() - lastWildcard - 1 >= 20;
    if (!safe) {
      throw new IllegalStateException(
          "app.cors.allowed-origin-patterns must be https and lowercase, with the wildcard only"
              + " inside the first label after a literal prefix of 8 or more characters and a"
              + " literal tail of 20 or more characters after the last wildcard: "
              + pattern);
    }
  }
}
