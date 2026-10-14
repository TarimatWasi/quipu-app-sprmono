package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * TAR-43: the real answers of the payments of the administrator (RF-05, CU-05, CU-14) satisfy the
 * shared contract, the one of the tag pinned in pom.xml. The bucket is a fake of the port; the day
 * is fixed at 2026-10-14 in Lima.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class PaymentsAdminContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/payments";
  private static final String ORIGIN = "http://localhost:3000";
  private static final byte[] PDF = "%PDF-1.7\n%test voucher".getBytes(StandardCharsets.US_ASCII);
  private static final String KEY = "vouchers/fake";
  private static final String SIGNED = "https://r2.test/vouchers/fake?X-Amz-Signature=s";

  /** 2026-10-14 09:00 in Lima. */
  @TestConfiguration
  static class FixedDay {
    @Bean
    @Primary
    Clock fixedClock() {
      return new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));
    }
  }

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean PaymentFilePort files;

  private final Long adminId = TestIds.next();
  private final Long guestAccountId = TestIds.next();
  private Long contractGuest;
  private Long temporaryGuest;
  private Long contract;
  private Long stay;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any(), any())).thenReturn(KEY);
    when(files.signedUrl(anyString(), any())).thenReturn(SIGNED);
    insertAccount(adminId, "ADMIN", "00000001", null);
    contractGuest = insertGuest("12345678", "CONTRACT", "ACTIVE", "Ana Quispe");
    temporaryGuest = insertGuest("87654321", "TEMPORARY", "ACTIVE", null);
    insertAccount(guestAccountId, "GUEST", "00000002", contractGuest);
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '201', 'ROOM', 'ACTIVE')",
        room);
    contract = insertContract(contractGuest, room, "2026-01-01", null);
    stay = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-10-01', '2026-10-20', 150)",
        stay,
        temporaryGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  /**
   * The oneOf branches of PaymentSummary, Payment and PaymentQueueItem list only the properties
   * that tell them apart; they are open in JSON Schema, but the validator reads each one as closed
   * and refuses the properties of the other branch. That one rule is ignored, everything else is
   * checked.
   */
  private static OpenApiInteractionValidator validator(ValidationErrorsWhitelist whitelist) {
    return OpenApiInteractionValidator.createFor(SPEC)
        .withLevelResolver(
            LevelResolver.create()
                .withLevel("validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
                .build())
        .withWhitelist(whitelist)
        .build();
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(validator(ValidationErrorsWhitelist.create()));
  }

  /**
   * MockMvc keeps the parts of a multipart request apart from its body: only the answer is checked.
   */
  private static ResultMatcher answersTheContract() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("MockMvc multipart request", WhitelistRules.isRequest())));
  }

  // ------------------------------------------------------------------ GET /admin/payments

  @Test
  void theQueueIsOldestFirstWithTheGuestTheEnvironmentAndNoAmount() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-02T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(URL).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].guestName").value("Ana Quispe"))
            .andExpect(jsonPath("$.content[0].documentNumber").value("12345678"))
            .andExpect(jsonPath("$.content[0].guestType").value("CONTRACT"))
            .andExpect(jsonPath("$.content[0].environmentCode").value("201"))
            .andExpect(jsonPath("$.content[0].period").value("2026-09"))
            .andExpect(jsonPath("$.content[0].amount").doesNotExist())
            .andExpect(jsonPath("$.content[1].guestType").value("TEMPORARY"))
            .andReturn();
    // The temporary guest has no environment, no period and, until the onboarding, no name: all
    // of them in the answer as an explicit null, because the contract requires the fields.
    Map<String, Object> temporary =
        JsonPath.read(result.getResponse().getContentAsString(), "$.content[1]");
    assertThat(temporary)
        .containsEntry("environmentCode", null)
        .containsEntry("period", null)
        .containsEntry("guestName", null);
  }

  @Test
  void theQueueFiltersByStatusAndPages() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-07", "PENDING", "2026-10-01T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-08", "APPROVED", "2026-10-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-03T10:00:00Z");

    mockMvc
        .perform(get(URL).param("status", "APPROVED").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
    var first =
        mockMvc
            .perform(get(URL).param("status", "PENDING").param("limit", "1").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content[0].period").value("2026-07"))
            .andReturn();
    String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    mockMvc
        .perform(
            get(URL)
                .param("status", "PENDING")
                .param("limit", "1")
                .param("after", cursor)
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].period").value("2026-09"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void aBadFilterOrPageParameterIsAValidationError() throws Exception {
    mockMvc
        .perform(get(URL).param("status", "SOON").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("status"));
    mockMvc
        .perform(get(URL).param("after", "nope").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("after"));
    mockMvc
        .perform(get(URL).param("limit", "0").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("limit"));
  }

  // ------------------------------------------------------------------ GET /admin/payments/{id}

  @Test
  void theDetailSignsTheVoucherWhenItIsRequestedAndIsNeverCached() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    mockMvc
        .perform(get(URL + "/" + masker.mask(IdKind.PAYMENT, id)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.voucherUrl").value(SIGNED))
        .andExpect(jsonPath("$.amount").value(800))
        .andExpect(
            jsonPath("$.contractId").value(masker.mask(IdKind.CONTRACT, contract).toString()))
        .andExpect(jsonPath("$.period").value("2026-09"));
    verify(files).signedUrl(KEY + "-" + id, VoucherType.PDF);
  }

  @Test
  void aPaymentOfAStayHasExplicitNullsForTheContractAndThePeriod() throws Exception {
    Long id = insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-01T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(URL + "/" + masker.mask(IdKind.PAYMENT, id)).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.stayId").value(masker.mask(IdKind.STAY, stay).toString()))
            .andReturn();
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("contractId", null).containsEntry("period", null);
  }

  @Test
  void anUnknownPaymentIsNotFoundWhetherTheIdIsForgedOrMissing() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    jdbc.update("DELETE FROM payments WHERE id = ?", id);

    for (UUID unknown :
        new UUID[] {
          masker.mask(IdKind.PAYMENT, id), UUID.randomUUID(), masker.mask(IdKind.CONTRACT, id)
        }) {
      mockMvc
          .perform(get(URL + "/" + unknown).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
    mockMvc.perform(get(URL + "/not-a-uuid").cookie(admin())).andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------------ PATCH /admin/payments/{id}

  @Test
  void approvingResolvesItWithTheReviewerOfTheSession() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.voucherUrl").doesNotExist())
        .andExpect(jsonPath("$.rejectionReason").doesNotExist());

    assertThat(
            jdbc.queryForObject("SELECT reviewed_by_id FROM payments WHERE id = ?", Long.class, id))
        .isEqualTo(adminId);
    assertThat(
            jdbc.queryForObject(
                    "SELECT reviewed_at FROM payments WHERE id = ?", java.sql.Timestamp.class, id)
                .toInstant())
        .isEqualTo(Instant.parse("2026-10-14T14:00:00Z"));
  }

  @Test
  void rejectingKeepsTheReasonAndLeavesThePeriodOpenForAnotherVoucher() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"  El monto no coincide  \"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("El monto no coincide"));

    // the period is open again: a new voucher can be pending for it
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-05T10:00:00Z");
  }

  @Test
  void aRejectionNeedsAReasonThatIsNotBlankAndNotTooLong() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    for (String body :
        new String[] {
          "{\"status\":\"REJECTED\"}",
          "{\"status\":\"REJECTED\",\"reason\":\"\"}",
          "{\"status\":\"REJECTED\",\"reason\":\"   \"}",
          "{\"status\":\"REJECTED\",\"reason\":\"\\u00A0\"}",
          "{\"status\":\"REJECTED\",\"reason\":null}"
        }) {
      resolve(id, body)
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("PAYMENT_REASON_REQUIRED"));
    }
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"" + "x".repeat(501) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("reason"));
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"mal\\u0000motivo\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("reason"));
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void theBodyIsClosedAndTheReviewerCannotBeNamedInIt() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"APPROVED\",\"reviewedById\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("reviewedById"));
    resolve(id, "{\"status\":\"PENDING\"}").andExpect(status().isBadRequest());
    resolve(id, "{}").andExpect(status().isBadRequest());
    resolve(id, "not json").andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void aPaymentIsResolvedOnlyOnce() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    resolve(id, "{\"status\":\"APPROVED\"}").andExpect(status().isOk());

    resolve(id, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_RESOLVED"));
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"Tarde\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_RESOLVED"));
  }

  @Test
  void resolvingAnUnknownPaymentIsNotFound() throws Exception {
    mockMvc
        .perform(
            patch(URL + "/" + UUID.randomUUID())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  // ------------------------------------------------------------------ POST /admin/payments

  @Test
  void aPaymentForAPeriodIsRegisteredApprovedAtOnce() throws Exception {
    MvcResult result =
        post(contractGuest, "period", "2026-10", "850.50", PDF, "../../v.pdf")
            .andExpect(status().isCreated())
            .andExpect(answersTheContract())
            .andExpect(
                saved ->
                    assertThat(saved.getResponse().getHeader("Location")).startsWith(URL + "/"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.period").value("2026-10"))
            .andExpect(jsonPath("$.amount").value(850.50))
            .andExpect(jsonPath("$.paymentMethod").value("Efectivo"))
            .andExpect(
                jsonPath("$.contractId").value(masker.mask(IdKind.CONTRACT, contract).toString()))
            .andReturn();
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("stayId", null).containsEntry("rejectionReason", null);
    verify(files).store(PDF, VoucherType.PDF);
    assertThat(jdbc.queryForObject("SELECT reviewed_by_id FROM payments", Long.class))
        .isEqualTo(adminId);
    assertThat(jdbc.queryForObject("SELECT recorded_by FROM payments", String.class))
        .isEqualTo("ADMIN");
    assertThat(jdbc.queryForObject("SELECT original_name FROM payments", String.class))
        .isEqualTo("v.pdf");
    assertThat(jdbc.queryForObject("SELECT voucher_key FROM payments", String.class))
        .isEqualTo(KEY);
  }

  @Test
  void aPaymentForAStayIsRegisteredApprovedAtOnce() throws Exception {
    post(
            temporaryGuest,
            "stayId",
            masker.mask(IdKind.STAY, stay).toString(),
            "150.50",
            PDF,
            "v.pdf")
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.stayId").value(masker.mask(IdKind.STAY, stay).toString()));
  }

  @Test
  void aFileThatIsNotAVoucherByItsContentIsRefused() throws Exception {
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    byte[] big = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, big, 0, PDF.length);
    byte[] active =
        "%PDF-1.7\n<< /Type /Catalog /OpenAction << /S /JavaScript /JS (x) >> >>"
            .getBytes(StandardCharsets.US_ASCII);

    for (byte[] content : new byte[][] {exe, big, active, new byte[0]}) {
      post(contractGuest, "period", "2026-10", "800", content, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"))
          .andExpect(jsonPath("$.field").value("file"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void bothOrNeitherOfPeriodAndStayAndABadAmountAreValidationErrors() throws Exception {
    postForm(builder(PDF).param("guestId", id(IdKind.GUEST, contractGuest)).param("amount", "800"))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("period", "2026-10")
                .param("stayId", id(IdKind.STAY, stay)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    for (String amount : new String[] {"0", "-1", "0.001", "100000000", "abc"}) {
      post(contractGuest, "period", "2026-10", amount, PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("amount"));
    }
    for (String period : new String[] {"2026-13", "2026-1", "202610", "oct-2026", "2026-00"}) {
      post(contractGuest, "period", period, "800", PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("period"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void aMisspelledFieldIsRefusedInsteadOfBeingIgnored() throws Exception {
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("periodo", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("periodo"));
    postForm(
            builder(PDF)
                .file(new MockMultipartFile("copy", PDF))
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("period", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("copy"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void theGuestAndTheStayMustExistAndTheGuestMustBeActive() throws Exception {
    post(999_999L, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    post(
            temporaryGuest,
            "stayId",
            masker.mask(IdKind.STAY, 999_999L).toString(),
            "150",
            PDF,
            "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    // a stay of another guest does not exist for this one
    post(contractGuest, "stayId", masker.mask(IdKind.STAY, stay).toString(), "150", PDF, "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    jdbc.update("UPDATE guests SET status = 'INACTIVE' WHERE id = ?", contractGuest);
    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(
            jsonPath("$.guestId").value(masker.mask(IdKind.GUEST, contractGuest).toString()));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodTheContractDoesNotChargeIsRefused() throws Exception {
    for (String period : new String[] {"2025-12", "2026-11"}) {
      post(contractGuest, "period", period, "800", PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("period"));
    }
    post(temporaryGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodWithAPaymentAlreadyApprovedOrPendingIsAConflictAndNeverA500() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-10-01T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-02T10:00:00Z");

    post(contractGuest, "period", "2026-09", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aStayWithAPaymentAlreadyApprovedIsAConflict() throws Exception {
    insertPayment(temporaryGuest, null, stay, null, "APPROVED", "2026-10-01T10:00:00Z");

    post(temporaryGuest, "stayId", masker.mask(IdKind.STAY, stay).toString(), "150", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
  }

  @Test
  void aRejectedVoucherDoesNotBlockARegisteredPayment() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-01T10:00:00Z");

    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf").andExpect(status().isCreated());
  }

  @Test
  void aBucketThatFailsRegistersNothing() throws Exception {
    when(files.store(any(), any())).thenThrow(new PaymentFileStorageException("down"));

    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  // ------------------------------------------------------------------ who may

  @Test
  void onlyAnAdministratorReachesThePaymentRoutes() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    String one = URL + "/" + masker.mask(IdKind.PAYMENT, id);

    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(URL).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(one).cookie(guest())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(one)
                .cookie(guest())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(one).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(builder(PDF).header("Origin", ORIGIN).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(builder(PDF).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
    verify(files, never()).store(any(), any());
  }

  @Test
  void aDirectPaymentThatDoesNotSayWhereItComesFromIsRefused() throws Exception {
    mockMvc
        .perform(builder(PDF).cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(builder(PDF).header("Origin", "https://evil.example").cookie(admin()))
        .andExpect(status().isForbidden());
    verify(files, never()).store(any(), any());
  }

  // ------------------------------------------------------------------ helpers

  private static ResultMatcher satisfiesTheContractToAnInvalidRequest() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("the request is invalid on purpose", WhitelistRules.isRequest())));
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  private Cookie guest() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(guestAccountId.toString()));
  }

  private String id(IdKind kind, Long id) {
    return masker.mask(kind, id).toString();
  }

  private ResultActions resolve(Long id, String body) throws Exception {
    return mockMvc.perform(
        patch(URL + "/" + masker.mask(IdKind.PAYMENT, id))
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static MockMultipartHttpServletRequestBuilder builder(byte[] content) {
    return multipart(URL).file(new MockMultipartFile("file", "v.pdf", "application/pdf", content));
  }

  private ResultActions postForm(MockMultipartHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request.header("Origin", ORIGIN).cookie(admin()));
  }

  /** A direct payment of that guest for a period or a stay, with the payment method Efectivo. */
  private ResultActions post(
      Long guestId, String targetName, String target, String amount, byte[] content, String name)
      throws Exception {
    return postForm(
        multipart(URL)
            .file(new MockMultipartFile("file", name, "application/pdf", content))
            .param("guestId", id(IdKind.GUEST, guestId))
            .param(targetName, target)
            .param("amount", amount)
            .param("paymentMethod", "Efectivo"));
  }

  private void insertAccount(Long id, String role, String document, @Nullable Long guestId) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, ?, 'DNI', ?, 'hash', ?, ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role,
        guestId);
  }

  private Long insertGuest(String number, String type, String status, @Nullable String name) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, ?, 'DNI', ?, ?, ?)",
        id,
        name,
        number,
        type,
        status);
    return id;
  }

  private Long insertContract(Long guest, Long environment, String start, @Nullable String end) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?,"
            + " ?::date, ?::date, 800, 5, 3, 'contracts/a.pdf', 'a.pdf', 10)",
        id,
        guest,
        environment,
        start,
        end);
    return id;
  }

  private Long insertPayment(
      Long guest,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable String period,
      String status,
      String createdDate) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, stay_id, period, amount, voucher_key,"
            + " original_name, content_type, size_bytes, status, rejection_reason, reviewed_at,"
            + " reviewed_by_id, recorded_by, created_date) VALUES (?, ?, ?, ?, ?::date, 800, ?,"
            + " 'v.pdf', 'application/pdf', 10, ?, CASE WHEN ? = 'REJECTED' THEN 'No coincide' END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE ?::bigint END, 'GUEST', ?::timestamptz)",
        id,
        guest,
        contractId,
        stayId,
        period == null ? null : period + "-01",
        KEY + "-" + id,
        status,
        status,
        status,
        status,
        adminId,
        createdDate);
    return id;
  }
}
