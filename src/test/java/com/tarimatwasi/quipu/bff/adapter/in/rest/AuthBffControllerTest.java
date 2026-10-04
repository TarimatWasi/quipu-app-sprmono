package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Login flow of the BFF against a real database and the bootstrapped ADMIN. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AuthBffControllerTest {

  private static final String LOGIN = "/bff/auth/login";
  private static final String CHANGE_PASSWORD = "/bff/auth/change-password";
  private static final String ME = "/bff/auth/me";
  private static final String LOGOUT = "/bff/auth/logout";

  private static final String ADMIN_LOGIN_BODY =
      """
      {"documentType":"DNI","documentNumber":"00000000","password":"Temporal123!"}
      """;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired JwtTokenProvider jwtTokenProvider;

  /** The PostgreSQL container is shared by all integration tests: this test owns its ADMIN. */
  @BeforeEach
  void adminExists() {
    jdbc.update("DELETE FROM users");
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000000', ?, 'ADMIN', TRUE, 'ACTIVE')",
        UUID.randomUUID(),
        passwordEncoder.encode("Temporal123!"));
  }

  @Test
  void loginWithAdminSucceedsAndSetsCookie() throws Exception {
    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(ADMIN_LOGIN_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.mustChangePassword").value(true))
        .andExpect(cookie().exists("sessionToken"))
        .andExpect(cookie().httpOnly("sessionToken", true));
  }

  /**
   * TAR-75: behind the Vercel rewrite the browser talks to its own origin, so the cookie is
   * first-party: SameSite=Lax, Secure and HttpOnly, with the lifetime of the JWT (480 minutes).
   */
  @Test
  void loginCookieIsLaxSecureHttpOnlyAndLastsAsLongAsTheToken() throws Exception {
    var setCookie =
        mockMvc
            .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(ADMIN_LOGIN_BODY))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

    assertThat(setCookie)
        .startsWith("sessionToken=")
        .contains("; Path=/")
        .contains("; Max-Age=28800")
        .contains("; Secure")
        .contains("; HttpOnly")
        .contains("; SameSite=Lax")
        .doesNotContain("SameSite=None");
  }

  @Test
  void loginWithWrongPasswordReturns401GenericError() throws Exception {
    var body =
        """
        {"documentType":"DNI","documentNumber":"00000000","password":"wrong"}
        """;

    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void loginWithUnknownDocumentReturnsSame401AsWrongPassword() throws Exception {
    var body =
        """
        {"documentType":"DNI","documentNumber":"99999999","password":"anything"}
        """;

    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void loginWithFormUrlencodedContentTypeIsRejectedBeforeLoginLogic() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("documentType=DNI&documentNumber=00000000&password=Temporal123!"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void crossSiteBodilessPostIsStoppedByCors() throws Exception {
    // A bodiless POST carries no Content-Type, so JsonOnlyFilter lets it through; a cross-site one
    // always sends Origin and exact-origin CORS rejects it (ADR-006, reviewed in TAR-59).
    mockMvc
        .perform(post(LOGIN).header("Origin", "https://evil.example.com"))
        .andExpect(status().isForbidden());
  }

  @Test
  void loginWithDisallowedOriginGetsCorsRejection() throws Exception {
    mockMvc
        .perform(
            post(LOGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ADMIN_LOGIN_BODY)
                .header("Origin", "https://evil.example.com"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  /** QP-SPRMONO-BFF-01: invalid input is a 400 with {code, message, field}, never a 500. */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          {"documentNumber":"00000000","password":"x"} | documentType
          {"documentType":null,"documentNumber":"00000000","password":"x"} | documentType
          {"documentType":"DNI","password":"x"} | documentNumber
          {"documentType":"DNI","documentNumber":"   ","password":"x"} | documentNumber
          {"documentType":"DNI","documentNumber":"00000000"} | password
          {"documentType":"DNI","documentNumber":"00000000","password":""} | password
          """)
  void loginWithMissingOrBlankFieldsReturns400WithTheFieldName(String body, String field)
      throws Exception {
    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value(field))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(cookie().doesNotExist("sessionToken"));
  }

  private Cookie loginCookie(String password) throws Exception {
    var body =
        """
        {"documentType":"DNI","documentNumber":"00000000","password":"%s"}
        """
            .formatted(password);
    return Objects.requireNonNull(
        mockMvc
            .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("sessionToken"));
  }

  private ResultActions changePassword(Cookie session, String body) throws Exception {
    return mockMvc.perform(
        post(CHANGE_PASSWORD)
            .cookie(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private boolean mustChangePasswordInTheDatabase() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT must_change_password FROM users WHERE document_number = '00000000'",
            Boolean.class));
  }

  /** RF-12: the forced change clears the flag, rotates the cookie and audits the user's id. */
  @Test
  void aForcedSessionChangesThePasswordAndGetsAFreshSession() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    var response =
        changePassword(forced, "{\"newPassword\":\"Nueva12345\"}")
            .andExpect(status().isNoContent())
            .andExpect(cookie().httpOnly("sessionToken", true))
            .andReturn()
            .getResponse();

    Cookie fresh = Objects.requireNonNull(response.getCookie("sessionToken"));
    assertThat(jwtTokenProvider.parse(fresh.getValue()))
        .hasValueSatisfying(s -> assertThat(s.mustChangePassword()).isFalse());
    assertThat(mustChangePasswordInTheDatabase()).isFalse();
    String userId =
        jdbc.queryForObject(
            "SELECT id::text FROM users WHERE document_number = '00000000'", String.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_modified_by FROM users WHERE document_number = '00000000'",
                String.class))
        .isEqualTo(userId);
    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(ADMIN_LOGIN_BODY))
        .andExpect(status().isUnauthorized());
    var newLogin =
        """
        {"documentType":"DNI","documentNumber":"00000000","password":"Nueva12345"}
        """;
    mockMvc
        .perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(newLogin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  /** The criterion of RF-12: the change cannot be skipped by calling the API directly. */
  @Test
  void aForcedSessionCannotReachAnythingButTheChange() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    mockMvc
        .perform(get("/bff/diagnostics/ping-services").cookie(forced))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  /** Rolling deployment: a token without the mark of an account that must change is not free. */
  @Test
  void aTokenFromBeforeTheMarkIsStillForcedWhenTheAccountMustChangeItsPassword() throws Exception {
    String id = jdbc.queryForObject("SELECT id::text FROM users", String.class);
    SecretKey signingKey =
        (SecretKey) Objects.requireNonNull(ReflectionTestUtils.getField(jwtTokenProvider, "key"));
    String legacy =
        Jwts.builder()
            .subject(id)
            .claim("role", "ADMIN")
            .expiration(Date.from(Instant.now().plusSeconds(600)))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();

    assertThat(jwtTokenProvider.parse(legacy)).as("legacy token parses").isPresent();
    mockMvc
        .perform(get("/bff/diagnostics/ping-services").cookie(new Cookie("sessionToken", legacy)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"));
  }

  /** A stale pending cookie must not lock the browser out of logging in again. */
  @Test
  void aForcedSessionCanStillLogInAgain() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    mockMvc
        .perform(
            post(LOGIN)
                .cookie(forced)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"00000000\",\"password\":\"Temporal123!\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void aShortPasswordIs400WithTheFieldAndTheForcedFlagStays() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    changePassword(forced, "{\"newPassword\":\"1234567\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_WEAK_PASSWORD"))
        .andExpect(jsonPath("$.field").value("newPassword"))
        .andExpect(jsonPath("$.message").value("Mínimo 8 caracteres"));
    assertThat(mustChangePasswordInTheDatabase()).isTrue();
  }

  @Test
  void keepingTheTemporaryPasswordIs400() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    changePassword(forced, "{\"newPassword\":\"Temporal123!\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_UNCHANGED"))
        .andExpect(jsonPath("$.field").value("newPassword"));
    assertThat(mustChangePasswordInTheDatabase()).isTrue();
  }

  @Test
  void aMissingNewPasswordIsAValidationError() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    changePassword(forced, "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("newPassword"));
  }

  /** TAR-74: /me answers the account as it is now, so a reload never trusts stale claims. */
  @Test
  void meAnswersTheCurrentStateOfTheAccount() throws Exception {
    Cookie forced = loginCookie("Temporal123!");

    mockMvc
        .perform(get(ME).cookie(forced))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.name").value("admin@example.test"))
        .andExpect(jsonPath("$.mustChangePassword").value(true));

    jdbc.update("UPDATE users SET must_change_password = FALSE");

    mockMvc
        .perform(get(ME).cookie(forced))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
  }

  @Test
  void meWithoutASessionIs401NoSession() throws Exception {
    mockMvc
        .perform(get(ME))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  @Test
  void meOfAnAccountDisabledAfterLoginIs401NoSession() throws Exception {
    Cookie session = loginCookie("Temporal123!");
    jdbc.update("UPDATE users SET status = 'INACTIVE'");

    mockMvc
        .perform(get(ME).cookie(session))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  @Test
  void logoutExpiresTheSessionCookieWithTheFlagsOfTheLogin() throws Exception {
    Cookie session = loginCookie("Temporal123!");

    var setCookie =
        mockMvc
            .perform(post(LOGOUT).cookie(session))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

    assertThat(setCookie)
        .startsWith("sessionToken=;")
        .contains("; Path=/")
        .contains("; Max-Age=0")
        .contains("; Secure")
        .contains("; HttpOnly")
        .contains("; SameSite=Lax");
  }

  @Test
  void logoutWithoutASessionIs401NoSession() throws Exception {
    mockMvc
        .perform(post(LOGOUT))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  @Test
  void changingThePasswordWithoutASessionIs401() throws Exception {
    mockMvc
        .perform(
            post(CHANGE_PASSWORD)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"Nueva12345\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** Outside the forced flow the current password is mandatory, as the contract says. */
  @Test
  void afterTheChangeTheCurrentPasswordIsRequired() throws Exception {
    Cookie forced = loginCookie("Temporal123!");
    Cookie fresh =
        Objects.requireNonNull(
            changePassword(forced, "{\"newPassword\":\"Nueva12345\"}")
                .andReturn()
                .getResponse()
                .getCookie("sessionToken"));

    changePassword(fresh, "{\"newPassword\":\"Otra123456\"}").andExpect(status().isUnauthorized());
    changePassword(fresh, "{\"currentPassword\":\"equivocada\",\"newPassword\":\"Otra123456\"}")
        .andExpect(status().isUnauthorized());
    changePassword(fresh, "{\"currentPassword\":\"Nueva12345\",\"newPassword\":\"Otra123456\"}")
        .andExpect(status().isNoContent());
  }
}
