package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** BE-SPR-SEC-05: the CORS origin is validated at startup. */
class SecurityConfigTest {

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:4200", "https://app.example.com"})
  void validateOrigin_exactOrigin_accepted(String origin) {
    assertThatCode(() -> SecurityConfig.validateOrigin(origin)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "https://*.example.com",
        "https://app.example.com/",
        "https://app.example.com/path",
        "app.example.com",
        "ftp://app.example.com",
        ""
      })
  void validateOrigin_wildcardPathOrOtherScheme_rejected(String origin) {
    assertThatThrownBy(() -> SecurityConfig.validateOrigin(origin))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validateOrigin_trailingSlash_messageNamesTheProperty() {
    assertThatThrownBy(() -> SecurityConfig.validateOrigin("https://a.example.com/"))
        .hasMessageContaining("app.cors.allowed-origin");
  }

  /** A typo must fail the startup, not silently never match (https://Host, :443, trailing dot). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://Host.example.com",
        "https://host.example.com:443",
        "https://host.example.com.",
        "https://user@host.example.com",
        "https://host.example.com#x",
        "https://host.example.com?x",
        "https://host_example.com",
        "https://host.example.com/"
      })
  void validateOrigin_deployedProfiles_rejectTyposInsteadOfNeverMatching(String origin) {
    assertThatThrownBy(() -> SecurityConfig.validateOrigin(origin, true))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://quipu-app-angweb-dev.vercel.app", "https://app.example.com"})
  void validateOrigin_deployedProfiles_acceptTheRealOrigins(String origin) {
    assertThatCode(() -> SecurityConfig.validateOrigin(origin, true)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"HTTP://localhost:4200", "http://Localhost:4200", "http://localhost:"})
  void validateOrigin_local_isStillStrictAboutCaseAndPort(String origin) {
    assertThatThrownBy(() -> SecurityConfig.validateOrigin(origin))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://app.example.com", "http://localhost:4200"})
  void validateOrigin_http_rejectedWhenHttpsIsRequired(String origin) {
    assertThatThrownBy(() -> SecurityConfig.validateOrigin(origin, true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("https");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://quipu-app-angweb-dev-git-*-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app"
      })
  void validateOriginPattern_wildcardInsideTheFirstLabelOnly_accepted(String pattern) {
    assertThatCode(() -> SecurityConfig.validateOriginPattern(pattern)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "*",
        "https://*",
        "https://*.com",
        "https://*.vercel.app",
        // the literal tail after the last wildcard must be at least 20 characters
        "https://quipu-app-*.vercel.app",
        "https://abcdefgh*.com",
        // wildcard only inside the first label
        "https://quipu-app-*.vercel.*",
        "https://quipu-app-angweb-*.*.vercel.app",
        "https://quipu-app-angweb-dev-shizukajikus-projects.vercel.*",
        "https://quipu-app-angweb-**-shizukajikus-projects.vercel.app",
        // https only, lowercase, no userinfo, port, path, query, fragment or trailing dot
        "http://quipu-app-angweb-*-shizukajikus-projects.vercel.app",
        "https://Quipu-app-angweb-*-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app:443",
        "https://user@quipu-app-angweb-*-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app.",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app/",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app?x",
        "https://quipu-app-angweb-*-shizukajikus-projects.vercel.app#x",
        "quipu-app-angweb-*-shizukajikus-projects.vercel.app",
        // no wildcard: it is an exact origin and belongs to app.cors.allowed-origins
        "https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app",
        ""
      })
  void validateOriginPattern_tooBroadOrMalformed_rejected(String pattern) {
    assertThatThrownBy(() -> SecurityConfig.validateOriginPattern(pattern))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.cors.allowed-origin-patterns");
  }

  private static final String PRIMARY = "https://quipu-app-angweb-dev.vercel.app";

  private static CorsConfiguration cors(
      String primary, List<String> origins, List<String> patterns, String... profiles) {
    var env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    var source =
        (UrlBasedCorsConfigurationSource)
            new SecurityConfig()
                .corsConfigurationSource(primary, new CorsProperties(origins, patterns), env);
    return Objects.requireNonNull(source.getCorsConfigurations().get("/**"));
  }

  private static CorsConfiguration vercelDev() {
    return cors(
        PRIMARY,
        List.of("https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app"),
        List.of("https://quipu-app-angweb-*-shizukajikus-projects.vercel.app"),
        "dev");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        PRIMARY,
        "https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-dev-git-development-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-dev-git-feat-tar-93ae08-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app"
      })
  void cors_allowsThePrimaryOriginTheExtraOnesAndTheMatchingPatterns(String origin) {
    assertThat(vercelDev().checkOrigin(origin)).isEqualTo(origin);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://quipu-app-angweb-dev.vercel.app.evil.com",
        "https://evil-quipu-app-angweb-dev.vercel.app",
        "https://evil-quipu-app-angweb-dev-git-x-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-dev-git-x-shizukajikus-projects.vercel.app.evil.com",
        "https://quipu-app-angweb-9n80jc9hm-otherteam-projects.vercel.app",
        "https://quipu-app-angweb-x.evil.com-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-dev-git-x.evil.com-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app:443",
        "https://QUIPU-APP-ANGWEB-9N80JC9HM-SHIZUKAJIKUS-PROJECTS.VERCEL.APP",
        "http://quipu-app-angweb-dev.vercel.app",
        "http://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app",
        "https://other-app-shizukajikus-projects.vercel.app",
        "null",
        "https://evil.example"
      })
  void cors_rejectsLookalikesAndForeignOrigins(String origin) {
    assertThat(vercelDev().checkOrigin(origin)).isNull();
  }

  /**
   * Known ceiling, accepted: a pattern bound to the team suffix cannot tell the hosts of the team
   * from a lookalike label that ends with the same suffix; Vercel allocates the labels and the
   * session cookie lives on the Vercel domain, so such an origin cannot get it from this backend.
   */
  @Test
  void cors_aLabelInsideTheTeamSuffixIsAcceptedByDesign() {
    var origin = "https://quipu-app-angweb-dev-git-main-evil-shizukajikus-projects.vercel.app";

    assertThat(vercelDev().checkOrigin(origin)).isEqualTo(origin);
  }

  @Test
  void cors_keepsCredentialsAndTheSameMethodsAndHeaders() {
    var config = vercelDev();

    assertThat(config.getAllowCredentials()).isTrue();
    assertThat(config.getAllowedHeaders()).containsExactly("Content-Type");
    assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE");
  }

  /**
   * TAR-131: the 423 and the 429 carry Retry-After; a cross-origin page can only read it if
   * exposed.
   */
  @Test
  void cors_exposesRetryAfterToTheBrowser() {
    assertThat(vercelDev().getExposedHeaders()).containsExactly("Retry-After");
  }

  @Test
  void cors_withoutExtrasBehavesAsBefore() {
    var config = cors("http://localhost:4200", List.of(), List.of(), "local");

    assertThat(config.checkOrigin("http://localhost:4200")).isEqualTo("http://localhost:4200");
    assertThat(config.checkOrigin("https://evil.example")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"dev", "prod"})
  void cors_strictProfilesRejectHttpOrigins(String profile) {
    assertThatThrownBy(() -> cors("http://app.example.com", List.of(), List.of(), profile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("https");
    assertThatThrownBy(() -> cors(PRIMARY, List.of("http://app.example.com"), List.of(), profile))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.cors.allowed-origins");
  }

  @Test
  void cors_aTooBroadPatternFailsTheStartup() {
    assertThatThrownBy(() -> cors(PRIMARY, List.of(), List.of("https://*.vercel.app"), "dev"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.cors.allowed-origin-patterns");
  }
}
