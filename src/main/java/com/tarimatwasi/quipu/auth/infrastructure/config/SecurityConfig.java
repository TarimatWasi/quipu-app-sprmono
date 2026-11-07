package com.tarimatwasi.quipu.auth.infrastructure.config;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.CookieBearerTokenResolver;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** HTTP security baseline (BE-SPR-SEC-01, SEC-02, SEC-04, SEC-05, SEC-09). */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

  /** Endpoints that need no session; a stale session cookie is not even read there. */
  private static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    "/bff/auth/csrf",
    "/bff/auth/login",
    "/bff/auth/activate",
    "/bff/auth/forgot-password",
    "/bff/auth/reset-password",
    "/error"
  };

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      AccountJwtAuthenticationConverter sessionConverter,
      RateLimitProperties rateLimit,
      EdgeProperties edge,
      SessionUserLimits sessionUserLimits,
      CookieCsrfTokenRepository csrfTokenRepository)
      throws Exception {
    // TAR-300: Spring owns token generation and validation; Angular supplies the cookie value
    // in X-XSRF-TOKEN. Explicit lifecycle handling is needed for our controller-based login.
    http.csrf(
            csrf ->
                csrf.spa()
                    .csrfTokenRepository(csrfTokenRepository)
                    .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                    .withObjectPostProcessor(new CookieSessionCsrfProtection()))
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
                    .requestMatchers("/bff/guest/**", "/bff/temporary-guest/**")
                    .hasRole("GUEST")
                    .anyRequest()
                    .authenticated())
        // Both run before the session is read, so a flood of bad requests costs no account lookup.
        .addFilterBefore(new RateLimitFilter(rateLimit), CsrfFilter.class)
        .addFilterBefore(new JsonOnlyFilter(), CsrfFilter.class);
    addSessionUserLimits(http, sessionUserLimits);
    addEdgeGate(http, edge);
    return http.build();
  }

  /**
   * TAR-144: first of the chain, before CORS and the limits: only what Vercel forwards gets in.
   * Without secrets (the local profile) there is no gate.
   */
  private static void addEdgeGate(HttpSecurity http, EdgeProperties edge) {
    if (edge.enabled()) {
      http.addFilterBefore(new EdgeGateFilter(edge), DisableEncodeUrlFilter.class);
    }
  }

  /** Host-only session cookie; only the CSRF cookie is readable by Angular. */
  @Bean
  CookieCsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieCustomizer(cookie -> cookie.secure(true).path("/").sameSite("Lax"));
    return repository;
  }

  /**
   * The limits per session user, once the request is authorized. Filters at the same place run in
   * the order they are added: the general limit of the ADMIN area first, then the specific ones,
   * which still apply on their own.
   */
  private static void addSessionUserLimits(HttpSecurity http, SessionUserLimits limits) {
    // TAR-221: counts every request of the session user to /bff/admin/**, of any method.
    http.addFilterAfter(new AdminAreaRateLimitFilter(limits.adminArea()), AuthorizationFilter.class)
        // TAR-206: counts the uploads of the session user, before the dispatcher reads the form.
        .addFilterAfter(new UploadRateLimitFilter(limits.uploads()), AuthorizationFilter.class)
        // RF-22, TAR-127: counts the creations, deactivations and reactivations of administrators;
        // TAR-229: and the changes of the account email of a guest.
        .addFilterAfter(
            new AdministratorRateLimitFilter(limits.administrators()), AuthorizationFilter.class)
        // TAR-222: counts the edits of the own profile, the routes of the guest that write data.
        .addFilterAfter(new ProfileRateLimitFilter(limits.profile()), AuthorizationFilter.class);
  }

  /** The limits per session user, together, so that the chain keeps few parameters (TAR-222). */
  record SessionUserLimits(
      AdminAreaRateLimitProperties adminArea,
      UploadRateLimitProperties uploads,
      AdministratorRateLimitProperties administrators,
      ProfileRateLimitProperties profile) {}

  @Bean
  SessionUserLimits sessionUserLimits(
      AdminAreaRateLimitProperties adminArea,
      UploadRateLimitProperties uploads,
      AdministratorRateLimitProperties administrators,
      ProfileRateLimitProperties profile) {
    return new SessionUserLimits(adminArea, uploads, administrators, profile);
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
