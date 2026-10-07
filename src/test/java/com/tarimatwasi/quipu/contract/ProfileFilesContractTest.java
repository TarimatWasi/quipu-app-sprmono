package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.bff.adapter.in.rest.SessionCookieProperties;
import com.tarimatwasi.quipu.shared.config.CorsProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The three Spring profiles (Perfil técnico de Quipu): local is the default and the only one with
 * defaults; dev and prod are strict and differ in the API documentation.
 */
class ProfileFilesContractTest {

  /** A placeholder with a default, such as {@code ${PORT:8080}}. */
  private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("\\$\\{[^}]*:[^}]*}");

  /**
   * The only optional variables of the strict profiles: once the first ADMIN exists they are no
   * longer needed (AdminBootstrap), so an empty default is legitimate.
   */
  private static final Pattern OPTIONAL_ADMIN_VARIABLE =
      Pattern.compile("\\$\\{ADMIN_(DOCUMENT_NUMBER|EMAIL):}");

  private static boolean hasDefault(String value) {
    return PLACEHOLDER_WITH_DEFAULT
        .matcher(OPTIONAL_ADMIN_VARIABLE.matcher(value).replaceAll(""))
        .find();
  }

  private static Map<String, Object> load(String file) throws IOException {
    var sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    var all = new java.util.HashMap<String, Object>();
    for (var source : sources) {
      var enumerable = (EnumerablePropertySource<?>) source;
      for (var name : enumerable.getPropertyNames()) {
        all.put(name, enumerable.getProperty(name));
      }
    }
    return all;
  }

  /**
   * ADR-F4 (TAR-126): the recovery email leaves in a virtual thread of Spring Boot's own executor,
   * bounded and waiting for the sends in progress when the service stops.
   */
  @Test
  void base_runsAsyncWorkOnBoundedVirtualThreads() throws IOException {
    var base = load("application.yml");

    assertThat(base.get("spring.threads.virtual.enabled")).isEqualTo(true);
    assertThat(base.get("spring.task.execution.simple.concurrency-limit")).isEqualTo(10);
    assertThat(base.get("spring.task.execution.simple.reject-tasks-when-limit-reached"))
        .isEqualTo(true);
    assertThat(base.get("spring.task.execution.shutdown.await-termination")).isEqualTo(true);
    assertThat(base.get("spring.task.execution.shutdown.await-termination-period")).isEqualTo("8s");
  }

  @Test
  void base_hasNoDefaultsAndApiDocsOff() throws IOException {
    var base = load("application.yml");

    assertThat(base.get("spring.profiles.default")).isEqualTo("local");
    assertThat(base.get("springdoc.api-docs.enabled")).isEqualTo(false);
    assertThat(base.get("springdoc.swagger-ui.enabled")).isEqualTo(false);
    assertThat(base.values().stream().map(String::valueOf))
        .noneMatch(ProfileFilesContractTest::hasDefault);
  }

  @ParameterizedTest
  @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
  void strictProfiles_haveNoDefaultsAndRequireTheAdmin(String file) throws IOException {
    var profile = load(file);

    assertThat(profile.values().stream().map(String::valueOf))
        .noneMatch(ProfileFilesContractTest::hasDefault);
    assertThat(profile.get("app.admin.required")).isEqualTo(true);
    assertThat(profile)
        .containsKeys("server.port", "spring.datasource.url", "app.cors.allowed-origin");
  }

  @ParameterizedTest
  @ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
  void noProfileOverridesTheErrorDetailsToExposed(String file) throws IOException {
    assertThat(load(file).entrySet())
        .filteredOn(e -> e.getKey().startsWith("server.error.include-"))
        .allMatch(e -> "never".equals(e.getValue()));
  }

  /**
   * The local profile is self-contained (TAR-69): the machine of a developer holds real credentials
   * in its environment, so no local value may be read from it. The only placeholder allowed is
   * Boot's own {@code random.*} source, which is not the environment.
   */
  @Test
  void local_referencesNoEnvironmentPlaceholder() throws IOException {
    assertThat(load("application-local.yml").values().stream().map(String::valueOf))
        .noneMatch(value -> Pattern.compile("\\$\\{(?!random\\.uuid})").matcher(value).find());
  }

  @Test
  void local_isFullyResolvableWithoutAnyEnvironment() throws IOException {
    // MockEnvironment has neither system properties nor environment variables: a placeholder that
    // needs JWT_SECRET, DATABASE_URL or similar cannot be resolved here and throws.
    var env = new MockEnvironment();
    var sources = new java.util.ArrayList<PropertySource<?>>();
    sources.addAll(
        new YamlPropertySourceLoader()
            .load("local", new ClassPathResource("application-local.yml")));
    sources.addAll(
        new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml")));
    sources.forEach(env.getPropertySources()::addLast);
    env.getPropertySources().addLast(new RandomValuePropertySource());

    var names = new java.util.TreeSet<String>();
    sources.forEach(
        s -> names.addAll(java.util.List.of(((EnumerablePropertySource<?>) s).getPropertyNames())));
    names.forEach(env::getProperty);

    assertThat(env.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:postgresql://localhost:5432/quipu");
    assertThat(env.getProperty("spring.datasource.username")).isEqualTo("quipu");
    assertThat(env.containsProperty("spring.datasource.password")).isFalse();
    assertThat(env.getProperty("server.port")).isEqualTo("8080");
    assertThat(env.getProperty("app.cors.allowed-origin")).isEqualTo("http://localhost:4200");
    assertThat(env.getProperty("app.jwt.secret")).hasSizeGreaterThanOrEqualTo(64);
  }

  @Test
  void apiDocs_onInLocalAndDev_offInProd() throws IOException {
    assertThat(load("application-local.yml").get("springdoc.api-docs.enabled")).isEqualTo(true);
    assertThat(load("application-dev.yml").get("springdoc.api-docs.enabled")).isEqualTo(true);
    assertThat(load("application-prod.yml").get("springdoc.api-docs.enabled")).isEqualTo(false);
    assertThat(load("application-prod.yml").get("springdoc.swagger-ui.enabled")).isEqualTo(false);
  }

  /** TAR-75: the session cookie is Lax in every profile unless a product overrides it. */
  @ParameterizedTest
  @ValueSource(strings = {"application.yml", "application-local.yml"})
  void sessionCookie_isLaxByDefault(String file) throws IOException {
    var all = load("application.yml");
    all.putAll(load(file));

    assertThat(all.get("app.session.same-site")).isEqualTo("lax");
  }

  /** TAR-75: the cookie and the token expire together, from the same property of the real YAML. */
  @Test
  void sessionCookieMaxAge_equalsTheJwtLifetimeOfTheRealConfiguration() throws IOException {
    var env = new MockEnvironment();
    new YamlPropertySourceLoader()
        .load("base", new ClassPathResource("application.yml"))
        .forEach(env.getPropertySources()::addLast);

    var cookie = Binder.get(env).bind("app.session", SessionCookieProperties.class).get();
    var jwt = Binder.get(env).bind("app.jwt.expiration", Duration.class).get();

    assertThat(jwt).isEqualTo(Duration.ofDays(30));
    assertThat(cookie.maxAge()).isEqualTo(jwt);
  }

  /**
   * TAR-75: the Vercel rewrite keeps the Origin of the Vercel host while the Host is the backend,
   * so dev lists the host of the project: the primary origin from CORS_ALLOWED_ORIGIN, the second
   * production alias and the branch and deployment aliases as anchored patterns. Nothing else.
   */
  @Test
  void dev_corsAllowsTheVercelAliasesAndTheDevDomainAndNothingElse() throws IOException {
    var primary = "https://quipu-app-angweb-dev.vercel.app";
    var env = new MockEnvironment().withProperty("CORS_ALLOWED_ORIGIN", primary);
    new YamlPropertySourceLoader()
        .load("dev", new ClassPathResource("application-dev.yml"))
        .forEach(env.getPropertySources()::addLast);

    var cors = Binder.get(env).bind("app.cors", CorsProperties.class).get();
    var config = cors.configuration(primary, true);

    List.of(
            primary,
            "https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app",
            "https://quipu-app-angweb-dev-git-development-shizukajikus-projects.vercel.app",
            "https://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app",
            "https://quipu-dev.tarimatwasi.com")
        .forEach(origin -> assertThat(config.checkOrigin(origin)).isEqualTo(origin));
    List.of(
            "https://quipu-dev.tarimatwasi.com.evil.com",
            "https://evil.quipu-dev.tarimatwasi.com",
            "http://quipu-dev.tarimatwasi.com",
            // the production domain is not an origin of dev
            "https://quipu.tarimatwasi.com",
            "https://quipu-app-angweb-dev.vercel.app.evil.com",
            "https://evil-quipu-app-angweb-dev-git-x-shizukajikus-projects.vercel.app",
            "https://quipu-app-angweb-9n80jc9hm-other-projects.vercel.app",
            "http://quipu-app-angweb-dev.vercel.app")
        .forEach(origin -> assertThat(config.checkOrigin(origin)).isNull());
  }

  @Test
  void prod_listsNoVercelPatternsYet() throws IOException {
    assertThat(load("application-prod.yml").keySet())
        .noneMatch(key -> key.startsWith("app.cors.allowed-origin-patterns"));
  }
}
