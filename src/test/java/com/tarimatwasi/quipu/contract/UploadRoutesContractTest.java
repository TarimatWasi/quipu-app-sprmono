package com.tarimatwasi.quipu.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
 * one and what happens to an upload that does not say where it comes from. A request that passes
 * the security reaches the controller, which reads the form and the guest of the session.
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
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyAGuestReachesTheGuestAndTheTemporaryGuestUploadRoutes() throws Exception {
    Long contractGuest = insertGuest("12345678", "CONTRACT");
    Long temporaryGuest = insertGuest("87654321", "TEMPORARY");
    Long onboardingGuest = insertGuest("11223344", "TEMPORARY");
    Map<String, Cookie> owners =
        Map.of(
            UploadRoutes.GUEST_PAYMENTS, guestAccount(contractGuest, "12345678"),
            UploadRoutes.TEMPORARY_GUEST_PAYMENTS, guestAccount(temporaryGuest, "87654321"),
            UploadRoutes.GUEST_ONBOARDING_DOCUMENTS, guestAccount(onboardingGuest, "11223344"));
    for (var owner : owners.entrySet()) {
      String route = owner.getKey();
      mockMvc
          .perform(upload(route).header("Origin", ORIGIN).cookie(admin()))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
      mockMvc.perform(upload(route).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
      // past the security and the guard of the area: the form is read (a voucher lacks its
      // amount; the one byte is not a document)
      mockMvc
          .perform(upload(route).header("Origin", ORIGIN).cookie(owner.getValue()))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.code")
                  .value(
                      route.equals(UploadRoutes.GUEST_ONBOARDING_DOCUMENTS)
                          ? "DOCUMENT_INVALID_FILE"
                          : "VALIDATION_ERROR"));
    }
  }

  private Long insertGuest(String document, String type) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, 'ACTIVE')",
        id,
        document,
        type);
    return id;
  }

  private Cookie guestAccount(Long guestId, String document) {
    Long id = TestIds.next();
    insertAccount(id, "GUEST", document);
    jdbc.update("UPDATE users SET guest_id = ? WHERE id = ?", guestId, id);
    return new Cookie("sessionToken", jwtTokenProvider.issue(id.toString()));
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
    // past the security: this account belongs to no guest, so the guard of the area refuses it
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
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
