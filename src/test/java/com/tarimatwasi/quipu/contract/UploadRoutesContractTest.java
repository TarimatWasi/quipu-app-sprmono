package com.tarimatwasi.quipu.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The upload routes through the whole security chain (ADR-006, RN-08, RN-09): who may reach each
 * one and what happens to an upload that does not say where it comes from. The routes of the
 * payments have no controller yet, so a request that passes the security answers 404.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class UploadRoutesContractTest {

  private static final String ORIGIN = "http://localhost:3000";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;

  private final Long adminId = TestIds.next();
  private final Long guestAccountId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertAccount(adminId, "ADMIN", "00000001");
    insertAccount(guestAccountId, "GUEST", "00000002");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  private Cookie guest() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(guestAccountId.toString()));
  }

  private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
      upload(String route) {
    return multipart(route)
        .file(new MockMultipartFile("file", "c.pdf", "application/pdf", new byte[] {1}));
  }

  @Test
  void anUploadWithoutOriginIsRefusedOnEveryUploadRouteWhoeverSendsIt() throws Exception {
    for (String route : UploadRoutes.ALL) {
      for (Cookie who : new Cookie[] {admin(), guest()}) {
        mockMvc
            .perform(upload(route).cookie(who))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
      }
    }
  }

  @Test
  void anUploadFromAnotherOriginIsStoppedByCorsOnEveryUploadRoute() throws Exception {
    for (String route : UploadRoutes.ALL) {
      mockMvc
          .perform(upload(route).header("Origin", "https://evil.example").cookie(admin()))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void onlyAnAdministratorReachesTheAdminUploadRoutes() throws Exception {
    for (String route : new String[] {UploadRoutes.CONTRACTS, UploadRoutes.ADMIN_PAYMENTS}) {
      mockMvc
          .perform(upload(route).header("Origin", ORIGIN).cookie(guest()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
      mockMvc.perform(upload(route).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(upload(UploadRoutes.ADMIN_PAYMENTS).header("Origin", ORIGIN).cookie(admin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void onlyAGuestReachesTheGuestAndTheTemporaryGuestUploadRoutes() throws Exception {
    for (String route :
        new String[] {UploadRoutes.GUEST_PAYMENTS, UploadRoutes.TEMPORARY_GUEST_PAYMENTS}) {
      mockMvc
          .perform(upload(route).header("Origin", ORIGIN).cookie(admin()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
      mockMvc.perform(upload(route).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
      mockMvc
          .perform(upload(route).header("Origin", ORIGIN).cookie(guest()))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void theTemporaryGuestAreaIsClosedToAnAdministratorAtTheUrlLayer() throws Exception {
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(guest()))
        .andExpect(status().isNotFound());
  }

  private void insertAccount(Long id, String role, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role);
  }
}
