package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TAR-300: real cookie/header CSRF flow, without the synthetic csrf() request post-processor. The
 * test-only property isolates the context: csrf() replaces CsrfFilter's repository globally.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.per-account-per-minute=1000",
      "app.rate-limit.per-minute=1000",
      "test.csrf.cookie-contract=true"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class CsrfContractTest {

  private static final String CSRF = "/bff/auth/csrf";
  private static final String LOGIN = "/bff/auth/login";
  private static final String PASSWORD = "Elegida12345";
  private static final String LOGIN_BODY =
      """
      {"documentType":"DNI","documentNumber":"00000123","password":"Elegida12345"}
      """;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired JwtTokenProvider tokens;

  private final Long userId = TestIds.next();

  @BeforeEach
  void accountExists() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, 'DNI', '00000123', ?, 'ADMIN',"
            + " FALSE, 'ACTIVE')",
        userId,
        passwords.encode(PASSWORD));
  }

  @AfterEach
  void clearAccounts() {
    TestTables.clear(jdbc);
  }

  @Test
  void bootstrapIsPublicUncacheableAndDoesNotCreateAnHttpSession() throws Exception {
    MvcResult result =
        mvc.perform(get(CSRF).cookie(new Cookie("sessionToken", "expired-or-malformed")))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(cookie().doesNotExist("sessionToken"))
            .andExpect(cookie().doesNotExist("JSESSIONID"))
            .andReturn();

    assertOneCsrfHeader(result);
    Cookie token = requiredCookie(result, "XSRF-TOKEN");
    assertThat(token.getValue()).isNotBlank();
    assertThat(token.isHttpOnly()).isFalse();
    assertThat(token.getSecure()).isTrue();
    assertThat(token.getPath()).isEqualTo("/");
    assertThat(token.getDomain()).isNull();
    assertThat(token.getAttribute("SameSite")).isEqualTo("Lax");
    assertThat(result.getRequest().getSession(false)).isNull();
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  @Test
  void bootstrapKeepsAnExistingTokenAndWorksWhilePasswordChangeIsPending() throws Exception {
    Cookie csrf = bootstrap();
    jdbc.update("UPDATE users SET must_change_password = TRUE WHERE id = ?", userId);
    mvc.perform(get(CSRF).cookie(csrf, session()))
        .andExpect(status().isNoContent())
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    login(csrf).andExpect(status().isOk());
  }

  @ParameterizedTest
  @CsvSource({
    "POST,/bff/auth/login",
    "POST,/bff/auth/activate",
    "POST,/bff/auth/forgot-password",
    "POST,/bff/auth/reset-password",
    "POST,/bff/auth/logout",
    "POST,/bff/admin/guests",
    "PUT,/bff/admin/guests/unknown",
    "PATCH,/bff/admin/guests/unknown",
    "DELETE,/bff/admin/guests/unknown"
  })
  void everyUnsafeMethodRequiresCsrfEvenOnPublicRoutes(String method, String path)
      throws Exception {
    mvc.perform(request(HttpMethod.valueOf(method), path).cookie(session()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
        .andExpect(cookie().doesNotExist("sessionToken"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  @Test
  void neitherACookieAloneNorAHeaderAloneNorAMismatchingHeaderOpensASession() throws Exception {
    Cookie csrf = bootstrap();
    mvc.perform(
            post(LOGIN).cookie(csrf).contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    mvc.perform(
            post(LOGIN)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    mvc.perform(
            post(LOGIN)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", "incorrect")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
        .andExpect(cookie().doesNotExist("sessionToken"));
  }

  @Test
  void loginRotatesCsrfWithoutOverwritingTheHttpOnlyJwtCookie() throws Exception {
    Cookie before = bootstrap();
    MvcResult result = login(before).andExpect(status().isOk()).andReturn();
    assertOneCsrfHeader(result);
    Cookie after = requiredCookie(result, "XSRF-TOKEN");
    Cookie jwt = requiredCookie(result, "sessionToken");

    assertThat(after.getValue()).isNotEqualTo(before.getValue());
    assertThat(jwt.isHttpOnly()).isTrue();
    assertThat(jwt.getSecure()).isTrue();
    mvc.perform(get("/bff/auth/me").cookie(jwt, after))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    mvc.perform(
            post("/bff/auth/logout").cookie(jwt, after).header("X-XSRF-TOKEN", after.getValue()))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge("sessionToken", 0))
        .andExpect(cookie().maxAge("XSRF-TOKEN", 0));
  }

  @Test
  void failedLoginDoesNotRotateTheExistingTokenOrIssueASession() throws Exception {
    Cookie csrf = bootstrap();
    mvc.perform(
            post(LOGIN)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY.replace(PASSWORD, "WrongPassword12345")))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().doesNotExist("sessionToken"))
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    login(csrf).andExpect(status().isOk());
  }

  @Test
  void choosingANewPasswordRotatesCsrfAndKeepsTheRenewedSessionUsable() throws Exception {
    MvcResult signedIn = login(bootstrap()).andReturn();
    Cookie oldCsrf = requiredCookie(signedIn, "XSRF-TOKEN");
    Cookie oldJwt = requiredCookie(signedIn, "sessionToken");
    MvcResult changed =
        mvc.perform(
                post("/bff/auth/change-password")
                    .cookie(oldCsrf, oldJwt)
                    .header("X-XSRF-TOKEN", oldCsrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"currentPassword\":\"Elegida12345\",\"newPassword\":\"Nueva12345\"}"))
            .andExpect(status().isNoContent())
            .andReturn();

    assertOneCsrfHeader(changed);
    Cookie renewedCsrf = requiredCookie(changed, "XSRF-TOKEN");
    Cookie renewedJwt = requiredCookie(changed, "sessionToken");
    assertThat(renewedCsrf.getValue()).isNotEqualTo(oldCsrf.getValue());
    mvc.perform(get("/bff/auth/me").cookie(oldJwt)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/bff/auth/logout")
                .cookie(renewedJwt, renewedCsrf)
                .header("X-XSRF-TOKEN", renewedCsrf.getValue()))
        .andExpect(status().isNoContent());
  }

  @Test
  void csrfDoesNotReplaceAuthenticationOrAuthorization() throws Exception {
    Cookie csrf = bootstrap();
    mvc.perform(post("/bff/auth/logout").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isUnauthorized());
    jdbc.update("UPDATE users SET role = 'GUEST' WHERE id = ?", userId);
    mvc.perform(
            post("/bff/admin/guests")
                .cookie(csrf, session())
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void multipartRequiresCsrfAndAValidTokenStillCannotBypassCors() throws Exception {
    Cookie csrf = bootstrap();
    mvc.perform(
            post("/bff/admin/contracts")
                .cookie(session())
                .header("Origin", "http://localhost:3000")
                .contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));
    mvc.perform(
            post("/bff/admin/contracts")
                .cookie(csrf, session())
                .header("X-XSRF-TOKEN", csrf.getValue())
                .header("Origin", "https://evil.example")
                .contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isForbidden());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM contracts", Integer.class)).isZero();
  }

  @Test
  void activationRotatesCsrfAndReturnsBothGuestCookies() throws Exception {
    Cookie csrf = bootstrap();
    MvcResult registered =
        mvc.perform(
                post("/bff/admin/guests")
                    .cookie(csrf, session())
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"documentType\":\"DNI\",\"documentNumber\":\"11112222\","
                            + "\"type\":\"CONTRACT\",\"accessMode\":\"LINK\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String url =
        JsonPath.read(registered.getResponse().getContentAsString(), "$.access.activationUrl");
    String activation = url.substring(url.indexOf("token=") + "token=".length());
    MvcResult activated =
        mvc.perform(
                post("/bff/auth/activate")
                    .cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"token\":\"%s\",\"newPassword\":\"Elegida12345\"}"
                            .formatted(activation)))
            .andExpect(status().isOk())
            .andReturn();

    assertOneCsrfHeader(activated);
    assertThat(requiredCookie(activated, "XSRF-TOKEN").getValue()).isNotEqualTo(csrf.getValue());
    mvc.perform(get("/bff/auth/me").cookie(requiredCookie(activated, "sessionToken")))
        .andExpect(status().isOk());
  }

  @Test
  void failedActivationAndPasswordChangeDoNotRotateCookies() throws Exception {
    Cookie csrf = bootstrap();
    mvc.perform(
            post("/bff/auth/activate")
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"unknown\",\"newPassword\":\"Elegida12345\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(cookie().doesNotExist("sessionToken"))
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    mvc.perform(
            post("/bff/auth/change-password")
                .cookie(csrf, session())
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"WrongPassword12345\",\"newPassword\":\"Nueva12345\"}"))
        .andExpect(status().isForbidden())
        .andExpect(cookie().doesNotExist("sessionToken"))
        .andExpect(cookie().doesNotExist("XSRF-TOKEN"));
    login(csrf).andExpect(status().isOk());
  }

  private static void assertOneCsrfHeader(MvcResult result) {
    assertThat(
            result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .count())
        .isEqualTo(1);
  }

  private Cookie bootstrap() throws Exception {
    return requiredCookie(
        mvc.perform(get(CSRF)).andExpect(status().isNoContent()).andReturn(), "XSRF-TOKEN");
  }

  private ResultActions login(Cookie csrf) throws Exception {
    return mvc.perform(
        post(LOGIN)
            .cookie(csrf)
            .header("X-XSRF-TOKEN", csrf.getValue())
            .contentType(MediaType.APPLICATION_JSON)
            .content(LOGIN_BODY));
  }

  private Cookie session() {
    return new Cookie("sessionToken", tokens.issue(userId.toString()));
  }

  private static Cookie requiredCookie(MvcResult result, String name) {
    return Objects.requireNonNull(result.getResponse().getCookie(name));
  }
}
