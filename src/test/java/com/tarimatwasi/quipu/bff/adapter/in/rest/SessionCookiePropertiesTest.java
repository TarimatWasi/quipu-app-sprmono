package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The SameSite attribute of the session cookie comes from {@code app.session.same-site}: a product
 * whose frontend lives on another registrable domain can choose {@code none}; the default is Lax.
 */
@SpringBootTest(
    properties = {"app.cors.allowed-origin=http://localhost:3000", "app.session.same-site=none"})
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SessionCookiePropertiesTest {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;

  @BeforeEach
  void adminExists() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000000', ?, 'ADMIN', FALSE, 'ACTIVE')",
        TestIds.next(),
        passwordEncoder.encode("Temporal123!"));
  }

  @Test
  void theConfiguredSameSiteValueIsUsed() throws Exception {
    var setCookie =
        mockMvc
            .perform(
                post("/bff/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"documentType":"DNI","documentNumber":"00000000","password":"Temporal123!"}
                        """))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

    assertThat(setCookie).contains("; SameSite=None").contains("; Secure").contains("; HttpOnly");
  }

  /** A zero or negative Max-Age would expire the cookie at once (or delete it): fail at startup. */
  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 59})
  void aMaxAgeShorterThanAMinuteIsRejected(long seconds) {
    assertThatThrownBy(
            () ->
                new SessionCookieProperties(
                    SessionCookieProperties.SameSite.LAX, Duration.ofSeconds(seconds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.session.max-age");
  }

  @Test
  void aMaxAgeOfOneMinuteOrMoreIsAccepted() {
    assertThatCode(
            () ->
                new SessionCookieProperties(
                    SessionCookieProperties.SameSite.LAX, Duration.ofMinutes(1)))
        .doesNotThrowAnyException();
  }

  // The Binder can hand a record a null for a property that is absent: the check must reject it.
  @SuppressWarnings("NullAway") // BE-SPR-NUL-02 TAR-148: intentional null from the Binder
  @Test
  void aMissingMaxAgeIsRejectedInsteadOfFallingBackToADivergentDefault() {
    assertThatThrownBy(
            () -> new SessionCookieProperties(SessionCookieProperties.SameSite.LAX, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.session.max-age");
  }
}
