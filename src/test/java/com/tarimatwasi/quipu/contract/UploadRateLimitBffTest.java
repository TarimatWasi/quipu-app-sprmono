package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * TAR-206 through the real security chain: the uploads of one session user are limited per minute,
 * after the session is read and before the form reaches the controller, and the 429 satisfies the
 * pinned contract.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.uploads-per-minute=1",
      "app.rate-limit.upload-burst=1"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class UploadRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String ORIGIN = "http://localhost:3000";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean ManageContractsUseCase contracts;

  private final Long adminId = TestIds.next();
  private final Long otherAdminId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insertAdmin(adminId, "00000001");
    insertAdmin(otherAdminId, "00000003");
    when(contracts.create(any())).thenThrow(new GuestNotFoundException());
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Cookie session(Long id) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(id.toString()));
  }

  /** A complete form: past the limit, the controller hands it to the use case. */
  private MockMultipartHttpServletRequestBuilder contract() {
    return multipart(UploadRoutes.CONTRACTS)
        .file(new MockMultipartFile("file", "c.pdf", "application/pdf", new byte[] {1}))
        .param("environmentId", masker.mask(IdKind.ENVIRONMENT, 1L).toString())
        .param("guestId", masker.mask(IdKind.GUEST, 1L).toString())
        .param("monthlyAmount", "500")
        .param("paymentDay", "5")
        .param("graceDays", "3")
        .param("startDate", "2026-11-01");
  }

  @Test
  void theUploadOverTheAllowanceGets429BeforeTheControllerReadsIt() throws Exception {
    mockMvc
        .perform(contract().header("Origin", ORIGIN).cookie(session(adminId)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(contract().header("Origin", ORIGIN).cookie(session(adminId)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(
            openApi()
                .isValid(
                    OpenApiInteractionValidator.createFor(SPEC)
                        .withWhitelist(
                            ValidationErrorsWhitelist.create()
                                .withRule("MockMvc multipart request", WhitelistRules.isRequest()))
                        .build()));

    verify(contracts, times(1)).create(any());
  }

  @Test
  void anotherUserKeepsItsOwnAllowanceAndNoSessionStillGets401() throws Exception {
    mockMvc.perform(contract().header("Origin", ORIGIN).cookie(session(adminId)));
    mockMvc
        .perform(contract().header("Origin", ORIGIN).cookie(session(adminId)))
        .andExpect(status().isTooManyRequests());

    mockMvc
        .perform(contract().header("Origin", ORIGIN).cookie(session(otherAdminId)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(contract().header("Origin", ORIGIN))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  private void insertAdmin(Long id, String document) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (?, ?, 'DNI', ?, 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        id,
        document + "@example.test",
        document);
  }
}
