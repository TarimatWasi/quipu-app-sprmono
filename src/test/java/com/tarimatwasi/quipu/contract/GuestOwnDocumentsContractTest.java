package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * TAR-242 (RN-37): the guest uploads more documents from «Mis datos» (H5, T3) after the onboarding,
 * against the shared contract. The bucket is a fake of the port.
 *
 * <p>The configuration is the one of GuestOnboardingContractTest and the guest document action
 * tests, so they share one cached context: every new context keeps its own connection pool against
 * the one test database (TAR-124).
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class GuestOwnDocumentsContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String ORIGIN = "http://localhost:3000";
  private static final String CONTRACT_PROFILE = "/bff/guest/profile";
  private static final String CONTRACT_DOCUMENTS = "/bff/guest/documents";
  private static final String TEMPORARY_DOCUMENTS = "/bff/temporary-guest/documents";
  private static final byte[] PDF = TestPdfs.plain();

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean GuestDocumentFilePort files;

  /** TAR-227: every account of a test signs in to this one session. */
  private final String sessionId = SessionIds.newSessionId();

  private final Long contractAccount = TestIds.next();
  private final Long temporaryAccount = TestIds.next();
  private Long contractGuest;
  private Long temporaryGuest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    contractGuest = insertGuest("12345678", "CONTRACT");
    temporaryGuest = insertGuest("87654321", "TEMPORARY");
    insertAccount(contractAccount, "12345678", contractGuest);
    insertAccount(temporaryAccount, "87654321", temporaryGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void aGuestUploadsOneMoreDocumentAndItShowsInItsProfile() throws Exception {
    when(files.store(any(), any())).thenReturn("guest-documents/nuevo");
    insertDocument(contractGuest, "dni.pdf");

    upload(CONTRACT_DOCUMENTS, guest())
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.originalName").value("dni.pdf"))
        .andExpect(jsonPath("$.contentType").value("application/pdf"))
        .andExpect(jsonPath("$.url").doesNotExist());

    verify(files).store(PDF, FileKind.PDF);
    assertThat(documentsOf(contractGuest)).isEqualTo(2);
    mockMvc
        .perform(get(CONTRACT_PROFILE).cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.documents.length()").value(2));
  }

  @Test
  void aTemporaryGuestUploadsOneMoreDocument() throws Exception {
    when(files.store(any(), any())).thenReturn("guest-documents/temporal");

    upload(TEMPORARY_DOCUMENTS, temporary())
        .andExpect(status().isCreated())
        .andExpect(answersTheContract());

    assertThat(documentsOf(temporaryGuest)).isEqualTo(1);
  }

  @Test
  void theSixthDocumentIsRefusedWithDocumentLimitReached() throws Exception {
    for (int i = 0; i < 5; i++) {
      insertDocument(contractGuest, "d" + i + ".pdf");
    }

    upload(CONTRACT_DOCUMENTS, guest())
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("DOCUMENT_LIMIT_REACHED"));

    assertThat(documentsOf(contractGuest)).isEqualTo(5);
    verify(files, never()).store(any(), any());
  }

  @Test
  void aFileThatIsNotAJpgPngOrPdfIsRefused() throws Exception {
    mockMvc
        .perform(
            multipart(CONTRACT_DOCUMENTS)
                .file(new MockMultipartFile("file", "nota.pdf", "application/pdf", new byte[] {1}))
                .header("Origin", ORIGIN)
                .cookie(guest()))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));

    assertThat(documentsOf(contractGuest)).isZero();
    verify(files, never()).store(any(), any());
  }

  @Test
  void anUploadOfTheOtherTypeOfGuestOrWhileOnboardingIsRefused() throws Exception {
    upload(TEMPORARY_DOCUMENTS, guest())
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    upload(CONTRACT_DOCUMENTS, temporary())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    jdbc.update("UPDATE guests SET status = 'ONBOARDING' WHERE id = ?", contractGuest);
    upload(CONTRACT_DOCUMENTS, guest())
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));

    assertThat(documentsOf(contractGuest)).isZero();
    assertThat(documentsOf(temporaryGuest)).isZero();
    verify(files, never()).store(any(), any());
  }

  @Test
  void anUploadWithoutOriginIsRefusedAsEveryMultipartRoute() throws Exception {
    mockMvc
        .perform(
            multipart(CONTRACT_DOCUMENTS)
                .file(new MockMultipartFile("file", "dni.pdf", "application/pdf", PDF))
                .cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    assertThat(documentsOf(contractGuest)).isZero();
    verify(files, never()).store(any(), any());
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

  private Cookie guest() {
    return session(contractAccount);
  }

  private Cookie temporary() {
    return session(temporaryAccount);
  }

  private Cookie session(Long accountId) {
    return new Cookie("sessionToken", jwtTokenProvider.renew(accountId.toString(), sessionId));
  }

  MaskSession sessionIds() {
    return masker.session(sessionId);
  }

  private ResultActions upload(String url, Cookie who) throws Exception {
    return mockMvc.perform(
        multipart(url)
            .file(new MockMultipartFile("file", "dni.pdf", "application/pdf", PDF))
            .header("Origin", ORIGIN)
            .cookie(who));
  }

  private Long insertGuest(String number, String type) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, full_name, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'Ana Quispe', ?, 'ACTIVE')",
        id,
        number,
        type);
    return id;
  }

  private void insertAccount(Long id, String document, @Nullable Long guestId) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', ?, 'hash', 'GUEST', ?, FALSE, 'ACTIVE')",
        id,
        document,
        guestId);
  }

  Long insertDocument(Long guest, String name) {
    Long document = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, ?, ?, 'application/pdf', 10)",
        document,
        guest,
        "guest-documents/" + document,
        name);
    return document;
  }

  private int documentsOf(Long guest) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM guest_documents WHERE guest_id = ?", Integer.class, guest);
    return count == null ? 0 : count;
  }
}
