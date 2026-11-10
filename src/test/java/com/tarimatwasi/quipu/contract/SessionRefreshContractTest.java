package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-228: the sessions renewed by refresh tokens, end to end and against the shared contract
 * (v0.25.0): a login hands both cookies, the access token lasts 15 minutes, a refresh rotates the
 * refresh token, a reuse after 30 seconds closes the session, and logout closes it on the server.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SessionRefreshContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String LOGIN = "/bff/auth/login";
  private static final String REFRESH = "/bff/auth/refresh";
  private static final String LOGOUT = "/bff/auth/logout";
  private static final String ME = "/bff/auth/me";
  private static final String DOCUMENT = "00000001";
  private static final String PASSWORD = "Elegida-12345";

  /** Its own clock, so that moving it does not touch the context of other tests. */
  @TestConfiguration
  static class TestClock {
    @Bean
    @Primary
    Clock sessionClock() {
      return new MutableClock(Instant.parse("2026-11-09T15:00:00Z"));
    }
  }

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired Clock clock;

  @BeforeEach
  void anAdministratorWithItsOwnPassword() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status, name)"
            + " VALUES (?, 'admin@example.test', 'DNI', ?, ?, 'ADMIN', FALSE, 'ACTIVE', ?)",
        TestIds.next(),
        DOCUMENT,
        passwordEncoder.encode(PASSWORD),
        "Ana Quispe Mamani");
  }

  private MutableClock clock() {
    return (MutableClock) clock;
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  /** For a request without the cookie on purpose: only that complaint is ignored. */
  private static ResultMatcher answersTheContractToARequestWithout(String requestErrorKey) {
    return openApi()
        .isValid(
            OpenApiInteractionValidator.createFor(SPEC)
                .withWhitelist(
                    ValidationErrorsWhitelist.create()
                        .withRule(
                            "the request lacks the cookie on purpose",
                            WhitelistRules.allOf(
                                WhitelistRules.isRequest(),
                                WhitelistRules.messageHasKey(requestErrorKey))))
                .build());
  }

  private MockHttpServletResponse login() throws Exception {
    return mockMvc
        .perform(
            post(LOGIN)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                        .formatted(DOCUMENT, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse();
  }

  private static Cookie cookie(MockHttpServletResponse response, String name) {
    return Objects.requireNonNull(response.getCookie(name), name);
  }

  private MockHttpServletResponse refresh(Cookie refreshToken) throws Exception {
    return mockMvc
        .perform(post(REFRESH).with(csrf()).cookie(refreshToken))
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse();
  }

  private int me(Cookie access) throws Exception {
    return mockMvc.perform(get(ME).cookie(access)).andReturn().getResponse().getStatus();
  }

  @Test
  void aLoginHandsAnAccessTokenOfFifteenMinutesAndARefreshTokenForTheAuthEndpointsOnly()
      throws Exception {
    MockHttpServletResponse login = login();

    Cookie access = cookie(login, "sessionToken");
    Cookie refreshToken = cookie(login, "refreshToken");
    assertThat(access.getMaxAge()).isEqualTo(900);
    assertThat(refreshToken.getMaxAge()).isEqualTo((int) Duration.ofDays(7).toSeconds());
    assertThat(refreshToken.getPath()).isEqualTo("/bff/auth");
    assertThat(refreshToken.isHttpOnly()).isTrue();
    assertThat(refreshToken.getSecure()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class,
                refreshToken.getValue()))
        .as("only the hash is stored")
        .isZero();
  }

  @Test
  void anExpiredAccessTokenIsRenewedWithTheRefreshTokenWhichRotates() throws Exception {
    MockHttpServletResponse login = login();
    Cookie first = cookie(login, "refreshToken");
    clock().advance(Duration.ofMinutes(16));
    assertThat(me(cookie(login, "sessionToken"))).as("the access token expired").isEqualTo(401);

    MockHttpServletResponse renewed = refresh(first);

    assertThat(renewed.getStatus()).isEqualTo(204);
    assertThat(me(cookie(renewed, "sessionToken"))).isEqualTo(200);
    assertThat(cookie(renewed, "refreshToken").getValue()).isNotEqualTo(first.getValue());
  }

  @Test
  void aRotatedRefreshTokenRacesForThirtySecondsThenItsReuseClosesTheSession() throws Exception {
    Cookie first = cookie(login(), "refreshToken");
    MockHttpServletResponse renewed = refresh(first);
    Cookie access = cookie(renewed, "sessionToken");

    clock().advance(Duration.ofSeconds(10));
    MockHttpServletResponse race = refresh(first);
    assertThat(race.getStatus()).as("another tab raced").isEqualTo(204);
    assertThat(race.getHeaders("Set-Cookie")).as("the browser keeps its new cookies").isEmpty();

    clock().advance(Duration.ofSeconds(25));
    mockMvc
        .perform(post(REFRESH).with(csrf()).cookie(first))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
    assertThat(me(access)).as("the session is closed at once").isEqualTo(401);
    assertThat(refresh(cookie(renewed, "refreshToken")).getStatus()).isEqualTo(401);
  }

  @Test
  void logoutClosesTheSessionOnTheServerEvenWithAnExpiredAccessToken() throws Exception {
    MockHttpServletResponse login = login();
    Cookie access = cookie(login, "sessionToken");
    Cookie refreshToken = cookie(login, "refreshToken");
    MockHttpServletResponse other = login();
    clock().advance(Duration.ofMinutes(16));

    mockMvc
        .perform(post(LOGOUT).with(csrf()).cookie(refreshToken, access))
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());

    assertThat(refresh(refreshToken).getStatus()).isEqualTo(401);
    assertThat(refresh(cookie(other, "refreshToken")).getStatus())
        .as("the other session of the account stays open")
        .isEqualTo(204);
  }

  @Test
  void aRefreshWithoutItsCookieIsNoSession() throws Exception {
    mockMvc
        .perform(post(REFRESH).with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"))
        .andExpect(answersTheContractToARequestWithout("validation.request.security.missing"));
  }
}
