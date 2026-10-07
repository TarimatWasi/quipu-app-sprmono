package com.tarimatwasi.quipu.auth.config;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtAuthenticationFilter;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** HTTP security baseline (BE-SPR-SEC-01, SEC-04, SEC-05, SEC-09). */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtTokenProvider jwtTokenProvider, RateLimitProperties rateLimit)
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
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/bff/auth/login",
                        "/bff/auth/forgot-password",
                        "/bff/auth/reset-password",
                        "/error")
                    .permitAll()
                    .requestMatchers("/bff/diagnostics/**", "/bff/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(new RateLimitFilter(rateLimit), AuthorizationFilter.class)
        .addFilterBefore(new JsonOnlyFilter(), AuthorizationFilter.class)
        .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), AuthorizationFilter.class);
    return http.build();
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

  /** No users yet: avoids Boot's generated default user (and its logged password). */
  @Bean
  UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
  }
}
