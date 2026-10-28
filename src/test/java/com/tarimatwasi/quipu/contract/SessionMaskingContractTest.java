package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * TAR-227 (D-75 b): a masked id is valid only in the session it was shown in. Each sign-in opens a
 * session with its own key; a password change keeps it; a token issued before the sessions existed
 * is no session at all.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SessionMaskingContractTest {

  private static final String URL = "/bff/admin/environments";
  private static final String DOCUMENT = "00000027";
  private static final String PASSWORD = "Actual-2026";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired JwtEncoder jwtEncoder;
  @Autowired IdMasker masker;
  @Autowired PasswordEncoder passwordEncoder;
  @Autowired Clock clock;

  private final Long adminId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, ?, 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId,
        DOCUMENT + "@example.test",
        DOCUMENT,
        passwordEncoder.encode(PASSWORD));
  }

  @Test
  void theLocationOfA201WorksInTheSessionThatGotIt() throws Exception {
    Cookie session = session();

    MvcResult created = create(session);
    String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
    String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

    assertThat(location).isEqualTo(URL + "/" + id);
    mockMvc
        .perform(get(Objects.requireNonNull(location)).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.code").value("201"));
  }

  @Test
  void anIdShownInOneSessionIsNotFoundInAnotherOfTheSameAccount() throws Exception {
    Cookie first = session();
    Cookie second = session();
    String id = JsonPath.read(create(first).getResponse().getContentAsString(), "$.id");

    MvcResult stale =
        mockMvc
            .perform(get(URL + "/" + id).cookie(second))
            .andExpect(status().isNotFound())
            .andReturn();
    MvcResult unknown =
        mockMvc
            .perform(get(URL + "/" + UUID.randomUUID()).cookie(second))
            .andExpect(status().isNotFound())
            .andReturn();

    // The same answer as an id that was never issued: a stale id tells nothing.
    assertThat(stale.getResponse().getContentAsString())
        .isEqualTo(unknown.getResponse().getContentAsString())
        .contains("ENVIRONMENT_NOT_FOUND");
    // The second session shows the same environment under an id of its own.
    MvcResult listed =
        mockMvc
            .perform(get(URL).param("status", "ALL").cookie(second))
            .andExpect(status().isOk())
            .andReturn();
    String shownToSecond =
        JsonPath.read(listed.getResponse().getContentAsString(), "$.content[0].id");
    assertThat(shownToSecond).isNotEqualTo(id);
    Long environment = SessionIds.of(masker, first).unmask(IdKind.ENVIRONMENT, UUID.fromString(id));
    assertThat(
            SessionIds.of(masker, second)
                .unmask(IdKind.ENVIRONMENT, UUID.fromString(shownToSecond)))
        .isEqualTo(environment);
  }

  @Test
  void aPasswordChangeKeepsTheSessionAndItsIds() throws Exception {
    Cookie signedIn = login();
    String id = JsonPath.read(create(signedIn).getResponse().getContentAsString(), "$.id");

    Cookie renewed =
        Objects.requireNonNull(
            mockMvc
                .perform(
                    post("/bff/auth/change-password")
                        .cookie(signedIn)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"currentPassword\":\""
                                + PASSWORD
                                + "\",\"newPassword\":\"Nueva-2026\"}"))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getCookie("sessionToken"));

    assertThat(renewed.getValue()).isNotEqualTo(signedIn.getValue());
    assertThat(SessionIds.sessionIdOf(renewed.getValue()))
        .isEqualTo(SessionIds.sessionIdOf(signedIn.getValue()));
    mockMvc.perform(get(URL + "/" + id).cookie(renewed)).andExpect(status().isOk());
    // A new sign-in is a new session: the id of the old one is unknown there.
    Cookie again = login("Nueva-2026");
    assertThat(SessionIds.sessionIdOf(again.getValue()))
        .isNotEqualTo(SessionIds.sessionIdOf(signedIn.getValue()));
    mockMvc.perform(get(URL + "/" + id).cookie(again)).andExpect(status().isNotFound());
  }

  /**
   * D-75 (2a): a token issued before the deployment names no session; its holder signs in again.
   */
  @Test
  void aTokenWithoutASessionIsAnInvalidSession() throws Exception {
    Instant now = clock.instant();
    String sessionId = SessionIds.newSessionId();
    String old =
        signed(
            JwtClaimsSet.builder()
                .subject(masker.session(sessionId).mask(IdKind.USER, adminId).toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build());

    MvcResult refused =
        mockMvc
            .perform(get(URL).cookie(new Cookie("sessionToken", old)))
            .andExpect(status().isUnauthorized())
            .andReturn();
    MvcResult garbage =
        mockMvc
            .perform(get(URL).cookie(new Cookie("sessionToken", "not-a-token")))
            .andExpect(status().isUnauthorized())
            .andReturn();

    assertThat(refused.getResponse().getContentAsString())
        .isEqualTo(garbage.getResponse().getContentAsString())
        .contains("AUTH_NO_SESSION");
    // The same subject in a token that names its session opens it: only the session was missing.
    String current =
        signed(
            JwtClaimsSet.builder()
                .subject(masker.session(sessionId).mask(IdKind.USER, adminId).toString())
                .claim("sid", sessionId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build());
    mockMvc
        .perform(get(URL).cookie(new Cookie("sessionToken", current)))
        .andExpect(status().isOk());
  }

  private Cookie session() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  private Cookie login() throws Exception {
    return login(PASSWORD);
  }

  private Cookie login(String password) throws Exception {
    return Objects.requireNonNull(
        mockMvc
            .perform(
                post("/bff/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"documentType\":\"DNI\",\"documentNumber\":\"%s\",\"password\":\"%s\"}"
                            .formatted(DOCUMENT, password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("sessionToken"));
  }

  private MvcResult create(Cookie session) throws Exception {
    return mockMvc
        .perform(
            post(URL)
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"201\",\"type\":\"ROOM\"}"))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private String signed(JwtClaimsSet claims) {
    return jwtEncoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
