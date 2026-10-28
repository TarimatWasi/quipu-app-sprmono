package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.PdfInspections;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-137 (RF-14, RF-20, RN-14, RN-33, RN-37; CU-11, CU-25..27): the mandatory, resumable
 * onboarding of a guest of either type and the documents the administrator reads, against the
 * shared contract of the tag pinned in pom.xml. The bucket is a fake of the port.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
@ExtendWith(OutputCaptureExtension.class)
class GuestOnboardingContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String ORIGIN = "http://localhost:3000";
  private static final String ONBOARDING = "/bff/guest/onboarding";
  private static final String PERSONAL_DATA = ONBOARDING + "/personal-data";
  private static final String DOCUMENTS = ONBOARDING + "/documents";
  private static final String DONE = DOCUMENTS + "/done";
  private static final String CONSENT = ONBOARDING + "/consent";
  private static final String KEY = "guest-documents/fake";
  private static final String SIGNED = "https://r2.test/guest-documents/fake?X-Amz-Signature=s";
  private static final byte[] PDF = TestPdfs.plain();
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1};
  private static final String SECRET_NAME = "dni-ana-quispe.pdf";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean GuestDocumentFilePort files;

  private final Long adminId = TestIds.next();
  private final Long contractAccount = TestIds.next();
  private final Long temporaryAccount = TestIds.next();
  private Long contractGuest;
  private Long temporaryGuest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any(), any())).thenReturn(KEY);
    when(files.signedUrl(anyString(), any())).thenReturn(SIGNED);
    insertAccount(adminId, "ADMIN", "00000001", null);
    contractGuest = insertGuest("12345678", "CONTRACT", "ONBOARDING");
    temporaryGuest = insertGuest("87654321", "TEMPORARY", "ONBOARDING");
    insertAccount(contractAccount, "GUEST", "12345678", contractGuest);
    insertAccount(temporaryAccount, "GUEST", "87654321", temporaryGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  // ------------------------------------------------------------------ the whole onboarding

  @Test
  void theOnboardingStartsWithThePersonalDataAndIsResumedWhereItWasLeft() throws Exception {
    MvcResult start =
        onboarding(guest())
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.status").value("ONBOARDING"))
            .andExpect(jsonPath("$.nextStep").value("PERSONAL_DATA"))
            .andExpect(jsonPath("$.personalData").doesNotExist())
            .andExpect(jsonPath("$.documents").isEmpty())
            .andExpect(jsonPath("$.consent.accepted").value(false))
            .andReturn();
    assertThat(read(start, "$.consent.text")).doesNotContainIgnoringCase("BORRADOR");

    personalData(guest(), "{\"name\":\"Ana Quispe\",\"phone\":\"987654321\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.nextStep").value("DOCUMENTS"))
        .andExpect(jsonPath("$.personalData.name").value("Ana Quispe"))
        .andExpect(jsonPath("$.personalData.contactEmail").doesNotExist());
    // Resumable: a new session finds the step where it was left.
    onboarding(guest())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.nextStep").value("DOCUMENTS"))
        .andExpect(jsonPath("$.personalData.phone").value("987654321"));
    assertThat(read(start, "$.consent.version")).isNotBlank();
  }

  @Test
  void aGuestCompletesTheOnboardingStepByStepAndBecomesActive() throws Exception {
    MvcResult start = onboarding(guest()).andReturn();
    String version = read(start, "$.consent.version");
    String text = read(start, "$.consent.text");
    savePersonalData();
    upload(guest(), PDF, "dni.pdf")
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.contentType").value("application/pdf"))
        .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
        .andExpect(jsonPath("$.url").doesNotExist());
    verify(files).store(PDF, FileKind.PDF);

    done(guest())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.nextStep").value("CONSENT"))
        .andExpect(jsonPath("$.documents.length()").value(1));
    accept(guest(), "true", version)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.nextStep").doesNotExist())
        .andExpect(jsonPath("$.consent.accepted").value(true))
        .andExpect(jsonPath("$.consent.version").value(version));

    assertTheConsentWasRecorded(version, text);
    onboarding(guest())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    // The rest of the area opens once the onboarding is complete.
    mockMvc.perform(get("/bff/guest/account-status").cookie(guest())).andExpect(status().isOk());
  }

  @Test
  void aTemporaryGuestTakesTheSameOnboarding() throws Exception {
    onboarding(temporary())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.nextStep").value("PERSONAL_DATA"));
    personalData(temporary(), "{\"name\":\"Luis\",\"phone\":\"999\"}").andExpect(status().isOk());
    done(temporary()).andExpect(status().isOk()).andExpect(jsonPath("$.documents").isEmpty());
    accept(temporary(), "true", currentVersion()).andExpect(status().isOk());
    assertThat(statusOf(temporaryGuest)).isEqualTo("ACTIVE");
  }

  // ------------------------------------------------------------------ the order (RN-33)

  @Test
  void aStepBeforeItsPredecessorIsOutOfOrderAndNothingIsSaved() throws Exception {
    upload(guest(), PDF, "dni.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_OUT_OF_ORDER"));
    done(guest())
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_OUT_OF_ORDER"));
    accept(guest(), "true", currentVersion())
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_OUT_OF_ORDER"));

    personalData(guest(), "{\"name\":\"Ana\",\"phone\":\"1\"}").andExpect(status().isOk());
    accept(guest(), "true", currentVersion())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_OUT_OF_ORDER"));
    done(guest()).andExpect(status().isOk());
    upload(guest(), PDF, "tarde.pdf")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ONBOARDING_STEP_OUT_OF_ORDER"));

    verify(files, never()).store(any(), any());
    assertThat(statusOf(contractGuest)).isEqualTo("ONBOARDING");
    assertThat(count("guest_consents")).isZero();
  }

  @Test
  void aCompletedOnboardingIsNotTakenAgain() throws Exception {
    jdbc.update(
        "UPDATE guests SET status = 'ACTIVE', full_name = 'Ana', phone = '1',"
            + " personal_data_completed_at = now(), documents_step_completed_at = now()");

    personalData(guest(), "{\"name\":\"Otra\",\"phone\":\"2\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    upload(guest(), PDF, "dni.pdf")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    done(guest())
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    accept(guest(), "true", currentVersion())
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ONBOARDING_ALREADY_COMPLETED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT full_name FROM guests WHERE id = ?", String.class, contractGuest))
        .isEqualTo("Ana");
  }

  // ------------------------------------------------------------------ the personal data

  @Test
  void thePersonalDataIsValidatedAndTheContactEmailIsUnique() throws Exception {
    personalData(guest(), "{\"phone\":\"1\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("name"));
    personalData(guest(), "{\"name\":\"Ana\",\"phone\":\"" + "9".repeat(31) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("phone"));
    personalData(guest(), "{\"name\":\"" + "a".repeat(201) + "\",\"phone\":\"1\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("name"));

    jdbc.update("UPDATE guests SET email = 'ana@example.com' WHERE id = ?", temporaryGuest);
    personalData(guest(), "{\"name\":\"Ana\",\"phone\":\"1\",\"contactEmail\":\"ANA@example.com\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("contactEmail"));
    assertThat(
            jdbc.queryForObject(
                "SELECT personal_data_completed_at IS NULL FROM guests WHERE id = ?",
                Boolean.class,
                contractGuest))
        .isTrue();

    personalData(
            guest(),
            "{\"name\":\" Ana Quispe \",\"phone\":\"1\",\"contactEmail\":\"ana.q@example.com\","
                + "\"emergencyContact\":{\"name\":\"Rosa\",\"phone\":\"2\"}}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.personalData.name").value("Ana Quispe"))
        .andExpect(jsonPath("$.personalData.contactEmail").value("ana.q@example.com"))
        .andExpect(jsonPath("$.personalData.emergencyContact.name").value("Rosa"))
        .andExpect(jsonPath("$.personalData.emergencyContact.relationship").doesNotExist());
  }

  // ------------------------------------------------------------------ the documents (RN-37)

  @Test
  void aDocumentIsCheckedByItsContentAndNeverNamedInTheLogs(CapturedOutput output)
      throws Exception {
    savePersonalData();

    // A text file that says it is a PDF.
    upload(guest(), "hola".getBytes(StandardCharsets.UTF_8), SECRET_NAME)
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"))
        .andExpect(jsonPath("$.field").value("file"));
    byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, tooLarge, 0, PDF.length);
    upload(guest(), tooLarge, SECRET_NAME)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));
    verify(files, never()).store(any(), any());

    MvcResult stored =
        upload(guest(), PNG, "../../" + SECRET_NAME + (char) 0x202E + ".png")
            .andExpect(status().isCreated())
            .andExpect(answersTheContract())
            .andExpect(jsonPath("$.contentType").value("image/png"))
            .andReturn();
    assertThat(read(stored, "$.originalName")).isEqualTo(SECRET_NAME + ".png");
    assertThat(jdbc.queryForObject("SELECT storage_key FROM guest_documents", String.class))
        .isEqualTo(KEY);
    assertThat(output.getAll()).doesNotContain(SECRET_NAME).doesNotContain(SIGNED);
  }

  @Test
  void anUnknownPartOfTheFormIsRefused() throws Exception {
    savePersonalData();
    mockMvc
        .perform(
            multipart(DOCUMENTS)
                .file(new MockMultipartFile("file", "d.pdf", "application/pdf", PDF))
                .param("guestId", UUID.randomUUID().toString())
                .header("Origin", ORIGIN)
                .cookie(guest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("guestId"));
    mockMvc
        .perform(
            multipart(DOCUMENTS)
                .file(new MockMultipartFile("other", "d.pdf", "application/pdf", PDF))
                .header("Origin", ORIGIN)
                .cookie(guest()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aGuestKeepsAtMostFiveDocuments() throws Exception {
    savePersonalData();
    for (int i = 0; i < 5; i++) {
      when(files.store(any(), any())).thenReturn(KEY + i);
      upload(guest(), PDF, "d" + i + ".pdf").andExpect(status().isCreated());
    }
    upload(guest(), PDF, "d5.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("DOCUMENT_LIMIT_REACHED"));
    verify(files, times(5)).store(any(), any());
    assertThat(count("guest_documents")).isEqualTo(5);
  }

  @Test
  void anUploadWithoutOriginIsRefused() throws Exception {
    savePersonalData();
    mockMvc
        .perform(
            multipart(DOCUMENTS)
                .file(new MockMultipartFile("file", "d.pdf", "application/pdf", PDF))
                .cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    verify(files, never()).store(any(), any());
  }

  // ------------------------------------------------------------------ the consent (RN-14)

  @Test
  void theConsentMustBeAcceptedInItsVersionInForce() throws Exception {
    savePersonalData();
    done(guest()).andExpect(status().isOk());

    accept(guest(), "false", currentVersion())
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("CONSENT_NOT_ACCEPTED"));
    accept(guest(), "true", "v-antigua")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("consentVersion"));
    assertThat(statusOf(contractGuest)).isEqualTo("ONBOARDING");
    assertThat(count("guest_consents")).isZero();
  }

  // ------------------------------------------------------------------ who may (RN-08, RN-09,
  // RN-33)

  @Test
  void everyOtherGuestRouteAsksForTheOnboardingFirst() throws Exception {
    for (String route : new String[] {"/bff/guest/account-status", "/bff/guest/contract"}) {
      mockMvc
          .perform(get(route).cookie(guest()))
          .andExpect(status().isForbidden())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    }
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(temporary()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  @Test
  void eachTypeOfGuestIsRefusedTheAreaOfTheOther() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ACTIVE'");
    mockMvc
        .perform(get("/bff/guest/contract").cookie(temporary()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void theOnboardingIsOnlyForAGuestThatChoseItsPassword() throws Exception {
    onboarding(admin())
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(ONBOARDING)).andExpect(status().isUnauthorized());
    jdbc.update("UPDATE users SET must_change_password = TRUE WHERE id = ?", contractAccount);
    onboarding(guest())
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_CHANGE_REQUIRED"));
  }

  /** The area comes from the parsed route, not from the raw text of the URI. */
  @Test
  void anEncodedPathDoesNotTakeAGuestIntoTheAreaOfTheOther() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ACTIVE'");
    int status =
        mockMvc
            .perform(get("/bff/temporary%2Dguest/payment-status").cookie(guest()))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(status).isIn(400, 403);
  }

  @Test
  void aFieldTheBodyDoesNotDefineIsRefused() throws Exception {
    personalData(guest(), "{\"name\":\"Ana\",\"phone\":\"1\",\"guestId\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("guestId"));
    personalData(
            guest(),
            "{\"name\":\"Ana\",\"phone\":\"1\",\"emergencyContact\":{\"name\":\"Rosa\","
                + "\"email<script>\":\"x\"}}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("emergencyContact.email_script_"));
    assertThat(statusOf(contractGuest)).isEqualTo("ONBOARDING");
    savePersonalData();
    done(guest()).andExpect(status().isOk());
    mockMvc
        .perform(
            post(CONSENT)
                .cookie(guest())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"accepted\":true,\"consentVersion\":\"%s\",\"guestId\":\"x\"}"
                        .formatted(currentVersion())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("guestId"));
    assertThat(count("guest_consents")).isZero();
  }

  // ------------------------------------------------------------------ the administrator (CU-27)

  @Test
  void theAdministratorListsTheDocumentsWithAShortLivedLink() throws Exception {
    savePersonalData();
    upload(guest(), PDF, "dni.pdf").andExpect(status().isCreated());
    String guestId = masker.mask(IdKind.GUEST, contractGuest).toString();

    MvcResult listed =
        mockMvc
            .perform(get("/bff/admin/guests/" + guestId + "/documents").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].url").value(SIGNED))
            .andExpect(jsonPath("$[0].originalName").value("dni.pdf"))
            .andReturn();
    assertThat(read(listed, "$[0].urlExpiresAt")).isNotBlank();
    verify(files).signedUrl(KEY, FileKind.PDF);
    mockMvc
        .perform(get("/bff/admin/guests/" + guestId).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentCount").value(1));

    mockMvc
        .perform(
            get("/bff/admin/guests/" + masker.mask(IdKind.GUEST, temporaryGuest) + "/documents")
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mockMvc
        .perform(get("/bff/admin/guests/" + UUID.randomUUID() + "/documents").cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    mockMvc
        .perform(get("/bff/admin/guests/" + guestId + "/documents").cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
  }

  // ------------------------------------------------------------------ helpers

  private static OpenApiInteractionValidator validator(ValidationErrorsWhitelist whitelist) {
    return OpenApiInteractionValidator.createFor(SPEC).withWhitelist(whitelist).build();
  }

  private static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(validator(ValidationErrorsWhitelist.create()));
  }

  /** MockMvc keeps the parts of a multipart request apart from its body: only the answer. */
  private static ResultMatcher answersTheContract() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("MockMvc multipart request", WhitelistRules.isRequest())));
  }

  private static ResultMatcher satisfiesTheContractToAnInvalidRequest() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("the request is invalid on purpose", WhitelistRules.isRequest())));
  }

  private Cookie admin() {
    return session(adminId);
  }

  private Cookie guest() {
    return session(contractAccount);
  }

  private Cookie temporary() {
    return session(temporaryAccount);
  }

  private Cookie session(Long accountId) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(accountId.toString()));
  }

  private ResultActions onboarding(Cookie who) throws Exception {
    return mockMvc.perform(get(ONBOARDING).cookie(who));
  }

  private ResultActions personalData(Cookie who, String body) throws Exception {
    return mockMvc.perform(
        put(PERSONAL_DATA).cookie(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private void savePersonalData() throws Exception {
    personalData(guest(), "{\"name\":\"Ana\",\"phone\":\"1\"}").andExpect(status().isOk());
  }

  private ResultActions upload(Cookie who, byte[] content, String name) throws Exception {
    return mockMvc.perform(
        multipart(DOCUMENTS)
            .file(new MockMultipartFile("file", name, "application/pdf", content))
            .header("Origin", ORIGIN)
            .cookie(who));
  }

  private ResultActions done(Cookie who) throws Exception {
    return mockMvc.perform(post(DONE).cookie(who));
  }

  private ResultActions accept(Cookie who, String accepted, String version) throws Exception {
    return mockMvc.perform(
        post(CONSENT)
            .cookie(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accepted\":%s,\"consentVersion\":\"%s\"}".formatted(accepted, version)));
  }

  private String currentVersion() throws Exception {
    return read(onboarding(guest()).andReturn(), "$.consent.version");
  }

  /** The guest is active and the consent row keeps the version and the hash of the text shown. */
  private void assertTheConsentWasRecorded(String version, String text) throws Exception {
    assertThat(statusOf(contractGuest)).isEqualTo("ACTIVE");
    Map<String, Object> consent =
        jdbc.queryForMap(
            "SELECT consent_version, text_sha256, accepted_at FROM guest_consents"
                + " WHERE guest_id = ?",
            contractGuest);
    assertThat(consent)
        .containsEntry("consent_version", version)
        .containsEntry("text_sha256", sha256(text));
    assertThat(consent.get("accepted_at")).isNotNull();
  }

  private static String read(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  private String statusOf(Long guestId) {
    return jdbc.queryForObject("SELECT status FROM guests WHERE id = ?", String.class, guestId);
  }

  private int count(String table) {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return count == null ? 0 : count;
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private void insertAccount(Long id, String role, String document, @Nullable Long guestId) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', ?, 'hash', ?, ?, FALSE, 'ACTIVE')",
        id,
        document,
        role,
        guestId);
  }

  private Long insertGuest(String number, String type, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, ?)",
        id,
        number,
        type,
        status);
    return id;
  }

  @Test
  void aDocumentThatCannotBeInspectedInTimeIsTooManyRequests() throws Exception {
    // TAR-39: the route declares 429 and no 503
    PdfInspections.whileBusy(
        () ->
            upload(guest(), TestPdfs.plain(), "dni.pdf")
                .andExpect(status().isTooManyRequests())
                .andExpect(answersTheContract())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")));
  }

  @Test
  void aDocumentOverFiveMegabytesIsRefusedBeforeItIsInspected() throws Exception {
    // TAR-39: with the inspection busy, a file read and inspected would answer 429
    savePersonalData();
    PdfInspections.whileBusy(
        () ->
            upload(guest(), TestPdfs.ofSize(6 * 1024 * 1024), "dni.pdf")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE")));
    verify(files, never()).store(any(), any());
  }
}
