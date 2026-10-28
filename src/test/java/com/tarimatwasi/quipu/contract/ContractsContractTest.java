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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * TAR-40: the real answers of the contracts (RF-03, RF-09, CU-03, CU-07, CU-12, CU-18) satisfy the
 * shared contract, the one of the tag pinned in pom.xml. The bucket is a fake of the port; the day
 * is fixed at 2026-10-14 in Lima.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class ContractsContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String URL = "/bff/admin/contracts";
  private static final byte[] PDF = TestPdfs.plain();
  private static final String KEY = "contracts/fake.pdf";
  private static final String SIGNED = "https://r2.test/contracts/fake.pdf?X-Amz-Signature=s";

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

  /** TAR-227: the accounts of a test sign in to one session, so its ids serve every request. */
  private final String sessionId = SessionIds.newSessionId();

  @Autowired Clock clock;
  @MockitoBean ContractFilePort files;

  private final Long adminId = TestIds.next();
  private final Long guestAccountId = TestIds.next();
  private Long room;
  private Long contractGuest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any())).thenReturn(KEY);
    when(files.signedUrl(anyString())).thenReturn(SIGNED);
    insertAccount(adminId, "ADMIN", "00000001", null);
    room = insertEnvironment("201", "ACTIVE");
    contractGuest = insertGuest("12345678", "CONTRACT", "ACTIVE");
    insertAccount(guestAccountId, "GUEST", "00000002", contractGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(SPEC);
  }

  /**
   * MockMvc keeps the parts of a multipart request apart from its body, so the validator would see
   * a body that is not there: only the answer is checked against the contract.
   */
  private static ResultMatcher answersTheContract() {
    var validator =
        OpenApiInteractionValidator.createFor(SPEC)
            .withWhitelist(
                ValidationErrorsWhitelist.create()
                    .withRule("MockMvc multipart request", WhitelistRules.isRequest()))
            .build();
    return openApi().isValid(validator);
  }

  // --- POST /admin/contracts ---

  @Test
  void aContractIsLoadedWithItsFileAndAnswersThe201() throws Exception {
    upload(room, contractGuest, PDF, "Contrato 201.pdf", "2026-11-01", "2027-10-31")
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(
            jsonPath("$.environmentId").value(ids().mask(IdKind.ENVIRONMENT, room).toString()))
        .andExpect(jsonPath("$.guestId").value(ids().mask(IdKind.GUEST, contractGuest).toString()))
        .andExpect(jsonPath("$.monthlyAmount").value(850.50))
        .andExpect(jsonPath("$.paymentDay").value(5))
        .andExpect(jsonPath("$.graceDays").value(3))
        .andExpect(jsonPath("$.startDate").value("2026-11-01"))
        .andExpect(jsonPath("$.endDate").value("2027-10-31"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.fileUrl").value(SIGNED));

    verify(files).store(PDF);
    // The row keeps the key of the object and never the signed URL.
    assertThat(jdbc.queryForObject("SELECT file_key FROM contracts", String.class)).isEqualTo(KEY);
    assertThat(jdbc.queryForObject("SELECT original_name FROM contracts", String.class))
        .isEqualTo("Contrato 201.pdf");
    assertThat(jdbc.queryForObject("SELECT size_bytes FROM contracts", Long.class))
        .isEqualTo((long) PDF.length);
  }

  @Test
  void anOpenEndedContractNeedsNoEndDate() throws Exception {
    upload(room, contractGuest, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.endDate").doesNotExist());
  }

  @Test
  void theNameOfTheFileCannotTraverseDirectories() throws Exception {
    upload(room, contractGuest, PDF, "../../etc/passwd", "2026-11-01", null)
        .andExpect(status().isCreated());

    assertThat(jdbc.queryForObject("SELECT original_name FROM contracts", String.class))
        .isEqualTo("passwd");
  }

  @Test
  void aProgramAPdfThatRunsSomethingOrAFileOverTenMegabytesIsRefused() throws Exception {
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    byte[] unreadable = "%PDF-1.7%fake".getBytes(StandardCharsets.US_ASCII);
    byte[] overTenMegabytes = java.util.Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);

    for (byte[] content :
        new byte[][] {exe, TestPdfs.withJavaScript(), unreadable, overTenMegabytes}) {
      upload(room, contractGuest, content, "contrato.pdf", "2026-11-01", null)
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("CONTRACT_INVALID_FILE"))
          .andExpect(jsonPath("$.field").value("file"));
    }

    verify(files, never()).store(any());
    assertThat(count("contracts")).isZero();
  }

  @Test
  void aRequestWithoutTheFileIsAValidationError() throws Exception {
    mockMvc
        .perform(
            fields(multipart(URL), room, contractGuest, OPEN_ENDED)
                .header("Origin", "http://localhost:3000")
                .cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("file"));
  }

  @Test
  void aFieldTheFormDoesNotDefineIsAValidationErrorNamingIt() throws Exception {
    mockMvc
        .perform(
            fields(multipartWith(PDF, "c.pdf"), room, contractGuest, OPEN_ENDED)
                .param("endDtae", "2027-10-31")
                .header("Origin", "http://localhost:3000")
                .cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("endDtae"));
    mockMvc
        .perform(
            fields(
                    multipartWith(PDF, "c.pdf").file(new MockMultipartFile("copy", PDF)),
                    room,
                    contractGuest,
                    OPEN_ENDED)
                .header("Origin", "http://localhost:3000")
                .cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("copy"));
    verify(files, never()).store(any());
    assertThat(count("contracts")).isZero();
  }

  @Test
  void theEndDateMustBeAfterTheStartDate() throws Exception {
    upload(room, contractGuest, PDF, "c.pdf", "2026-11-01", "2026-11-01")
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("CONTRACT_INVALID_DATES"));
    verify(files, never()).store(any());
  }

  @Test
  void aFieldOutOfRangeIsAValidationErrorNamingIt() throws Exception {
    assertRefused(new Terms("0", "5", "3", "2026-11-01", null), "monthlyAmount");
    assertRefused(new Terms("800", "32", "3", "2026-11-01", null), "paymentDay");
    assertRefused(new Terms("800", "5", "x", "2026-11-01", null), "graceDays");
    assertRefused(new Terms("800", "5", "3", "01/11/2026", null), "startDate");
  }

  private void assertRefused(Terms terms, String field) throws Exception {
    mockMvc
        .perform(
            fields(multipartWith(PDF, "c.pdf"), room, contractGuest, terms)
                .header("Origin", "http://localhost:3000")
                .cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value(field));
  }

  @Test
  void anUnknownEnvironmentOrGuestIsNotFound() throws Exception {
    upload(999_999L, contractGuest, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isNotFound())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_NOT_FOUND"));
    upload(room, 999_999L, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isNotFound())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    verify(files, never()).store(any());
  }

  @Test
  void aGuestThatIsNotActiveCannotTakeAContract() throws Exception {
    Long pending = insertGuest("87654321", "CONTRACT", "PENDING_ACTIVATION");

    upload(room, pending, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(jsonPath("$.guestId").value(ids().mask(IdKind.GUEST, pending).toString()));
  }

  @Test
  void aTemporaryGuestCannotTakeAContract() throws Exception {
    Long temporary = insertGuest("87654322", "TEMPORARY", "ACTIVE");

    upload(room, temporary, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_CONTRACT"));
  }

  @Test
  void anEnvironmentWithAContractInForceIsOccupiedUntilTheContractIsFinished() throws Exception {
    upload(room, contractGuest, PDF, "c.pdf", "2026-11-01", null).andExpect(status().isCreated());
    Long second = insertGuest("87654323", "CONTRACT", "ACTIVE");

    upload(room, second, PDF, "c2.pdf", "2026-12-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_OCCUPIED"));
    verify(files).store(PDF); // only the first one reached the bucket

    terminate(contractId()).andExpect(status().isNoContent());
    upload(room, second, PDF, "c2.pdf", "2026-12-01", null).andExpect(status().isCreated());
  }

  /** TAR-200: an inactive environment takes no contract, and nothing reaches the bucket. */
  @Test
  void anInactiveEnvironmentCannotTakeAContract() throws Exception {
    Long closed = insertEnvironment("301", "INACTIVE");
    upload(closed, contractGuest, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_INACTIVE"))
        .andExpect(jsonPath("$.message").isNotEmpty());
    verify(files, never()).store(any());
    assertThat(count("contracts")).isZero();
  }

  /** TAR-200: guest state, guest type and debt are answered before the state of the environment. */
  @Test
  void theGuestAndItsDebtAreCheckedBeforeTheEnvironmentIsInactive() throws Exception {
    Long closed = insertEnvironment("301", "INACTIVE");
    Long pending = insertGuest("87654321", "CONTRACT", "PENDING_ACTIVATION");
    upload(closed, pending, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"));
    Long temporary = insertGuest("87654322", "TEMPORARY", "ACTIVE");
    upload(closed, temporary, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_CONTRACT"));
    // An expired contract in another room left July to September unpaid.
    insertContract(contractGuest, room, "2026-07-01", "2026-09-15", "ACTIVE");
    upload(closed, contractGuest, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_HAS_PENDING_DEBT"));
    verify(files, never()).store(any());
  }

  @Test
  void anExpiredContractLeavesTheEnvironmentFree() throws Exception {
    insertContract(contractGuest, room, "2020-01-01", "2020-12-31", "ACTIVE");
    Long second = insertGuest("87654323", "CONTRACT", "ACTIVE");

    upload(room, second, PDF, "c2.pdf", "2026-12-01", null).andExpect(status().isCreated());
  }

  @Test
  void aBucketThatFailsLeavesNoContract() throws Exception {
    when(files.store(any())).thenThrow(new ContractFileStorageException("down"));

    upload(room, contractGuest, PDF, "c.pdf", "2026-11-01", null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
    assertThat(count("contracts")).isZero();
  }

  // --- GET /admin/contracts ---

  @Test
  void theContractsAreListedNewestFirstWithTheStateOfTheDayAndNoFileUrl() throws Exception {
    Long guest2 = insertGuest("22222222", "CONTRACT", "ACTIVE");
    Long guest3 = insertGuest("33333333", "CONTRACT", "ACTIVE");
    Long guest4 = insertGuest("44444444", "CONTRACT", "ACTIVE");
    Long room2 = insertEnvironment("202", "ACTIVE");
    Long room3 = insertEnvironment("203", "ACTIVE");
    Long room4 = insertEnvironment("204", "ACTIVE");
    insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");
    insertContract(guest2, room2, "2025-01-01", "2026-10-30", "ACTIVE"); // 16 days left
    insertContract(guest3, room3, "2025-01-01", "2026-01-31", "ACTIVE"); // expired
    insertContract(guest4, room4, "2025-01-01", "2027-12-31", "TERMINATED");

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(4))
        .andExpect(jsonPath("$.content[0].status").value("TERMINATED"))
        .andExpect(jsonPath("$.content[1].status").value("EXPIRED"))
        .andExpect(jsonPath("$.content[2].status").value("ACTIVE"))
        .andExpect(jsonPath("$.content[2].expiringSoon").value(true))
        .andExpect(jsonPath("$.content[3].status").value("ACTIVE"))
        .andExpect(jsonPath("$.content[3].expiringSoon").value(false))
        .andExpect(jsonPath("$.content[0].fileUrl").doesNotExist());
    verify(files, never()).signedUrl(anyString());
  }

  @Test
  void theListFiltersByTheDerivedStatusAndPages() throws Exception {
    Long guest2 = insertGuest("22222222", "CONTRACT", "ACTIVE");
    Long room2 = insertEnvironment("202", "ACTIVE");
    insertContract(contractGuest, room, "2025-01-01", "2026-01-31", "ACTIVE");
    insertContract(guest2, room2, "2025-01-01", null, "ACTIVE");

    mockMvc
        .perform(get(URL).param("status", "EXPIRED").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("EXPIRED"));
    var first =
        mockMvc
            .perform(get(URL).param("limit", "1").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andReturn();
    String cursor =
        com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    mockMvc
        .perform(get(URL).param("limit", "1").param("after", cursor).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
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
  }

  // --- GET /admin/contracts/{id} and terminate ---

  @Test
  void theDetailSignsTheFileUrlWhenItIsRequested() throws Exception {
    Long id = insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");

    mockMvc
        .perform(get(URL + "/" + ids().mask(IdKind.CONTRACT, id)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.fileUrl").value(SIGNED))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    verify(files).signedUrl("contracts/seed.pdf");
  }

  @Test
  void anUnknownContractIsNotFoundWhetherTheIdIsForgedOrMissing() throws Exception {
    Long id = insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");
    jdbc.update("DELETE FROM contracts WHERE id = ?", id);

    for (UUID unknown : new UUID[] {ids().mask(IdKind.CONTRACT, id), UUID.randomUUID()}) {
      mockMvc
          .perform(get(URL + "/" + unknown).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
      mockMvc
          .perform(post(URL + "/" + unknown + "/terminate").cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract());
    }
    mockMvc.perform(get(URL + "/not-a-uuid").cookie(admin())).andExpect(status().isBadRequest());
  }

  @Test
  void terminatingStoresTheInstantOnceAndAnotherTerminateDoesNotChangeIt() throws Exception {
    Long id = insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");

    terminate(id).andExpect(status().isNoContent());
    Instant first = terminatedAt(id);
    var fixedDay = (MutableClock) clock;
    fixedDay.advance(java.time.Duration.ofDays(3));
    try {
      terminate(id).andExpect(status().isNoContent());
    } finally {
      fixedDay.advance(java.time.Duration.ofDays(-3));
    }

    assertThat(first).isEqualTo(Instant.parse("2026-10-14T14:00:00Z"));
    assertThat(terminatedAt(id)).isEqualTo(first);
  }

  private Instant terminatedAt(Long id) {
    return jdbc.queryForObject(
            "SELECT terminated_at FROM contracts WHERE id = ?", java.sql.Timestamp.class, id)
        .toInstant();
  }

  @Test
  void terminatingIsIdempotentAndAnExpiredContractStaysExpired() throws Exception {
    Long active = insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");
    Long guest2 = insertGuest("22222222", "CONTRACT", "ACTIVE");
    Long expired =
        insertContract(
            guest2, insertEnvironment("202", "ACTIVE"), "2020-01-01", "2020-12-31", "ACTIVE");

    terminate(active).andExpect(status().isNoContent()).andExpect(satisfiesTheContract());
    terminate(active).andExpect(status().isNoContent());
    terminate(expired).andExpect(status().isNoContent());

    assertThat(storedStatus(active)).isEqualTo("TERMINATED");
    assertThat(storedStatus(expired)).isEqualTo("ACTIVE");
    mockMvc
        .perform(get(URL + "/" + ids().mask(IdKind.CONTRACT, expired)).cookie(admin()))
        .andExpect(jsonPath("$.status").value("EXPIRED"));
  }

  // --- the environment that a contract holds ---

  @Test
  void anEnvironmentHeldByAContractCannotBeDeactivated() throws Exception {
    Long id = insertContract(contractGuest, room, "2025-01-01", null, "ACTIVE");
    String deactivate =
        "/bff/admin/environments/" + ids().mask(IdKind.ENVIRONMENT, room) + "/deactivate";

    mockMvc
        .perform(post(deactivate).cookie(admin()))
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ENVIRONMENT_OCCUPIED"));

    terminate(id).andExpect(status().isNoContent());
    mockMvc
        .perform(post(deactivate).cookie(admin()))
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
  }

  // --- GET /guest/contract ---

  @Test
  void aGuestReadsItsOwnContractWhateverItsState() throws Exception {
    Long id = insertContract(contractGuest, room, "2025-01-01", "2026-01-31", "ACTIVE");

    mockMvc
        .perform(get("/bff/guest/contract").cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.id").value(ids().mask(IdKind.CONTRACT, id).toString()))
        .andExpect(jsonPath("$.status").value("EXPIRED"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.fileUrl").value(SIGNED));
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = now() WHERE id = ?", id);
    mockMvc
        .perform(get("/bff/guest/contract").cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TERMINATED"));
  }

  @Test
  void aGuestNeverSeesTheContractOfAnother() throws Exception {
    Long guest2 = insertGuest("22222222", "CONTRACT", "ACTIVE");
    insertContract(guest2, room, "2025-01-01", null, "ACTIVE");

    mockMvc
        .perform(get("/bff/guest/contract").cookie(guest()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(content().string(""));
  }

  // --- who may ---

  @Test
  void onlyTheRightRoleReachesEachRoute() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContractToAnInvalidRequest());
    mockMvc.perform(get(URL).cookie(guest())).andExpect(status().isForbidden());
    mockMvc
        .perform(get(URL + "/" + UUID.randomUUID()).cookie(guest()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(URL + "/" + UUID.randomUUID() + "/terminate").cookie(guest()))
        .andExpect(status().isForbidden());
    upload(room, contractGuest, PDF, "c.pdf", "2026-11-01", null, guest())
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/bff/guest/contract")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/bff/guest/contract").cookie(admin())).andExpect(status().isForbidden());
    verify(files, never()).store(any());
  }

  // --- multipart is allowed on one route only (ADR-006) ---

  @Test
  void multipartIsRefusedWithA415OnEveryOtherRoute() throws Exception {
    mockMvc
        .perform(
            multipart("/bff/admin/environments")
                .file(new MockMultipartFile("file", "c.pdf", "application/pdf", PDF))
                .cookie(admin()))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    mockMvc
        .perform(
            multipart(URL + "/" + UUID.randomUUID() + "/terminate")
                .file(new MockMultipartFile("file", "c.pdf", "application/pdf", PDF))
                .cookie(admin()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void anUploadThatDoesNotSayWhereItComesFromIsRefused() throws Exception {
    mockMvc
        .perform(multipartWith(PDF, "c.pdf").cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(multipartWith(PDF, "c.pdf").header("Sec-Fetch-Site", "cross-site").cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract());
    verify(files, never()).store(any());
  }

  @Test
  void anUploadFromTheSameSiteNeedsNoOriginHeader() throws Exception {
    mockMvc
        .perform(
            fields(
                    multipartWith(PDF, "c.pdf"),
                    room,
                    contractGuest,
                    new Terms("850.50", "5", "3", "2026-11-01", null))
                .header("Sec-Fetch-Site", "same-origin")
                .cookie(admin()))
        .andExpect(status().isCreated());
  }

  @Test
  void aCrossSiteUploadIsStoppedByCors() throws Exception {
    mockMvc
        .perform(
            multipartWith(PDF, "c.pdf").header("Origin", "https://evil.example").cookie(admin()))
        .andExpect(status().isForbidden());
    verify(files, never()).store(any());
  }

  // --- helpers ---

  private static ResultMatcher satisfiesTheContractToAnInvalidRequest() {
    var validator =
        OpenApiInteractionValidator.createFor(SPEC)
            .withWhitelist(
                ValidationErrorsWhitelist.create()
                    .withRule("the request is invalid on purpose", WhitelistRules.isRequest()))
            .build();
    return openApi().isValid(validator);
  }

  /** The ids as the session of the test shows them. */
  private MaskSession ids() {
    return masker.session(sessionId);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(adminId.toString(), sessionId));
  }

  private Cookie guest() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(guestAccountId.toString(), sessionId));
  }

  private MockMultipartHttpServletRequestBuilder multipartWith(byte[] content, String name) {
    return multipart(URL).file(new MockMultipartFile("file", name, "application/pdf", content));
  }

  /** What the form carries besides the ids and the file; the strings are sent as they are. */
  private record Terms(
      String amount, String paymentDay, String graceDays, String start, @Nullable String end) {}

  private static final Terms OPEN_ENDED = new Terms("850.50", "5", "3", "2026-11-01", null);

  private MockMultipartHttpServletRequestBuilder fields(
      MockMultipartHttpServletRequestBuilder request, Long environment, Long guest, Terms terms) {
    request
        .param("environmentId", ids().mask(IdKind.ENVIRONMENT, environment).toString())
        .param("guestId", ids().mask(IdKind.GUEST, guest).toString())
        .param("monthlyAmount", terms.amount())
        .param("paymentDay", terms.paymentDay())
        .param("graceDays", terms.graceDays())
        .param("startDate", terms.start());
    if (terms.end() != null) {
      request.param("endDate", terms.end());
    }
    return request;
  }

  private ResultActions upload(
      Long environment, Long guest, byte[] content, String name, String start, @Nullable String end)
      throws Exception {
    return upload(environment, guest, content, name, start, end, admin());
  }

  private ResultActions upload(
      Long environment,
      Long guest,
      byte[] content,
      String name,
      String start,
      @Nullable String end,
      Cookie cookie)
      throws Exception {
    return mockMvc.perform(
        fields(
                multipartWith(content, name),
                environment,
                guest,
                new Terms("850.50", "5", "3", start, end))
            .header("Origin", "http://localhost:3000")
            .cookie(cookie));
  }

  private ResultActions terminate(Long id) throws Exception {
    return mockMvc.perform(
        post(URL + "/" + ids().mask(IdKind.CONTRACT, id) + "/terminate").cookie(admin()));
  }

  private Long contractId() {
    return jdbc.queryForObject("SELECT max(id) FROM contracts", Long.class);
  }

  private String storedStatus(Long id) {
    return jdbc.queryForObject("SELECT status FROM contracts WHERE id = ?", String.class, id);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private void insertAccount(Long id, String role, String document, Long guestId) {
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

  private Long insertEnvironment(String code, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, 'ROOM', ?)",
        id,
        code,
        status);
    return id;
  }

  private Long insertGuest(String number, String type, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'Ana Quispe', 'DNI', ?, ?, ?)",
        id,
        number,
        type,
        status);
    return id;
  }

  /** The creation date grows with the id, so "newest first" is the order of the inserts. */
  private Long insertContract(
      Long guest, Long environment, String start, @Nullable String end, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes, status, terminated_at,"
            + " created_date) VALUES (?, ?, ?, ?::date, ?::date, 800, 5, 3, 'contracts/seed.pdf',"
            + " 'seed.pdf', 10, ?, CASE WHEN ? = 'TERMINATED' THEN now() END,"
            + " now() + (? * interval '1 millisecond'))",
        id,
        guest,
        environment,
        start,
        end,
        status,
        status,
        id % 1_000_000);
    return id;
  }
}
