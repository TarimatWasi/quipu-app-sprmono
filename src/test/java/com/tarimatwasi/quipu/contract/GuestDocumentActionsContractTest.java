package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.service.GuestDocumentPurger;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.PdfInspections;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * TAR-187 (RN-37): the administrator deletes or replaces a document of a guest only on an ARCO
 * request or an upload error, and each action leaves evidence of who and when, against the shared
 * contract of the tag pinned in pom.xml. The bucket is a fake of the port.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
@ExtendWith(OutputCaptureExtension.class)
class GuestDocumentActionsContractTest {

  private static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  private static final String ORIGIN = "http://localhost:3000";
  private static final String DNI = "45678912";
  private static final String NAME = "Rosa Mamani";
  private static final String OLD_KEY = "guest-documents/old-secret";
  private static final String NEW_KEY = "guest-documents/new-secret";
  private static final String SIGNED = "https://r2.test/guest-documents/new?X-Amz-Signature=s";
  private static final String FILE_NAME = "dni-rosa-mamani.pdf";
  private static final byte[] PDF = TestPdfs.plain();
  private static final String UPLOAD_ERROR = "{\"reason\":\"UPLOAD_ERROR\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  private @Nullable Cookie admin;
  @MockitoBean GuestDocumentFilePort files;
  @MockitoSpyBean GuestDocumentActionRepositoryPort records;
  @Autowired GuestDocumentPurger purger;

  private final Long adminId = TestIds.next();
  private final Long guestAccount = TestIds.next();
  private Long guestId;
  private Long otherGuestId;
  private Long documentId;
  private Long otherDocumentId;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files, records);
    when(files.store(any(), any())).thenReturn(NEW_KEY);
    when(files.signedUrl(anyString(), any())).thenReturn(SIGNED);
    insertAccount(adminId, "ADMIN", "00000001", null);
    guestId = insertGuest(DNI);
    otherGuestId = insertGuest("87654321");
    insertAccount(guestAccount, "GUEST", DNI, guestId);
    documentId = insertDocument(guestId, OLD_KEY);
    otherDocumentId = insertDocument(otherGuestId, "guest-documents/other");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  // ------------------------------------------------------------------ deletion

  @Test
  void anArcoDeletionRemovesTheFileAndTheRowAndLeavesEvidenceOfWhoAndWhen(CapturedOutput output)
      throws Exception {
    delete(
            guestId,
            documentId,
            "{\"reason\":\"ARCO_REQUEST\",\"requestedAt\":\"2026-01-15\","
                + "\"requestChannel\":\"WHATSAPP\",\"note\":\"ticket 7\"}")
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"));

    verify(files).delete(OLD_KEY);
    assertThat(documentsOf(guestId)).isZero();
    actions(guestId)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].action").value("DELETE"))
        .andExpect(jsonPath("$.content[0].documentId").value(masked(documentId)))
        .andExpect(jsonPath("$.content[0].replacementDocumentId").isEmpty())
        .andExpect(jsonPath("$.content[0].reason").value("ARCO_REQUEST"))
        .andExpect(jsonPath("$.content[0].requestedAt").value("2026-01-15"))
        .andExpect(jsonPath("$.content[0].requestChannel").value("WHATSAPP"))
        .andExpect(jsonPath("$.content[0].note").value("ticket 7"))
        .andExpect(jsonPath("$.content[0].contentType").value("application/pdf"))
        .andExpect(jsonPath("$.content[0].sizeBytes").value(10))
        .andExpect(
            jsonPath("$.content[0].actorId")
                .value(adminIds().mask(IdKind.USER, adminId).toString()))
        .andExpect(jsonPath("$.content[0].performedAt").isNotEmpty())
        .andExpect(jsonPath("$.nextCursor").isEmpty());
    assertThat(output).doesNotContain(DNI, NAME, OLD_KEY, FILE_NAME, "ticket 7");
  }

  @Test
  void anUploadErrorTakesNeitherDateNorChannelAndABlankNoteCountsAsAbsent() throws Exception {
    mockMvc
        .perform(
            post(deletion(guestId, documentId))
                .cookie(admin())
                .header("Origin", ORIGIN)
                .contentType("application/json;charset=UTF-8")
                .content("{\"reason\":\"UPLOAD_ERROR\",\"note\":\"   \"}"))
        .andExpect(status().isNoContent());

    actions(guestId)
        .andExpect(jsonPath("$.content[0].requestedAt").isEmpty())
        .andExpect(jsonPath("$.content[0].requestChannel").isEmpty())
        .andExpect(jsonPath("$.content[0].note").isEmpty());
  }

  @Test
  void eachBrokenRuleOfTheReasonIsAValidationErrorWithItsField() throws Exception {
    String future = LocalDate.now(ZoneId.of("America/Lima")).plusDays(1).toString();
    Map<String, String> bodies =
        Map.of(
            "{}",
            "reason",
            "{\"reason\":\"OTHER\"}",
            "reason",
            "{\"reason\":\"ARCO_REQUEST\",\"requestChannel\":\"EMAIL\"}",
            "requestedAt",
            "{\"reason\":\"ARCO_REQUEST\",\"requestedAt\":\"2026-01-15\"}",
            "requestChannel",
            "{\"reason\":\"ARCO_REQUEST\",\"requestedAt\":\""
                + future
                + "\",\"requestChannel\":\"EMAIL\"}",
            "requestedAt",
            "{\"reason\":\"UPLOAD_ERROR\",\"requestChannel\":\"EMAIL\"}",
            "requestChannel",
            "{\"reason\":\"UPLOAD_ERROR\",\"note\":\"" + "x".repeat(501) + "\"}",
            "note",
            "{\"reason\":\"UPLOAD_ERROR\",\"extra\":1}",
            "extra");
    for (var body : bodies.entrySet()) {
      delete(guestId, documentId, body.getKey())
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value(body.getValue()));
    }
    verify(files, never()).delete(anyString());
    assertThat(documentsOf(guestId)).isOne();
  }

  @Test
  void theBodyIsCheckedBeforeTheIdsAreUnmasked() throws Exception {
    mockMvc
        .perform(
            post("/bff/admin/guests/"
                    + UUID.randomUUID()
                    + "/documents/"
                    + UUID.randomUUID()
                    + "/deletion")
                .cookie(admin())
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("reason"));
  }

  @Test
  void aDocumentOfAnotherGuestOrAForgedIdIsNotFoundAndIsNotTouched() throws Exception {
    List<String> routes =
        List.of(
            deletion(guestId, otherDocumentId),
            deletion(otherGuestId, documentId),
            "/bff/admin/guests/"
                + UUID.randomUUID()
                + "/documents/"
                + masked(documentId)
                + "/deletion",
            "/bff/admin/guests/"
                + adminIds().mask(IdKind.GUEST, guestId)
                + "/documents/"
                + UUID.randomUUID()
                + "/deletion");
    for (String route : routes) {
      mockMvc
          .perform(
              post(route)
                  .cookie(admin())
                  .header("Origin", ORIGIN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(UPLOAD_ERROR))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
          .andExpect(jsonPath("$.message").value("El documento no existe"));
    }
    verify(files, never()).delete(anyString());
    assertThat(documentsOf(guestId)).isOne();
    assertThat(documentsOf(otherGuestId)).isOne();
    assertThat(count("guest_document_actions")).isZero();
  }

  @Test
  void neitherTheGuestNorAnAnonymousRequestReachesItEvenForItsOwnDocument() throws Exception {
    mockMvc
        .perform(
            post(deletion(guestId, documentId))
                .cookie(session(guestAccount))
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPLOAD_ERROR))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(
            post(deletion(guestId, documentId))
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPLOAD_ERROR))
        .andExpect(status().isUnauthorized());
    replace(session(guestAccount), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(actionsRoute(guestId)).cookie(session(guestAccount)))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract());
    mockMvc.perform(get(actionsRoute(guestId))).andExpect(status().isUnauthorized());
    verify(files, never()).delete(anyString());
    assertThat(documentsOf(guestId)).isOne();
  }

  @Test
  void theDeletionSaysWhereItComesFromAndTakesJsonOnly() throws Exception {
    mockMvc
        .perform(
            post(deletion(guestId, documentId))
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPLOAD_ERROR))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(
            post(deletion(guestId, documentId))
                .cookie(admin())
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPLOAD_ERROR))
        .andExpect(status().isForbidden());
    for (String type :
        List.of(
            "text/plain", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x")) {
      mockMvc
          .perform(
              post(deletion(guestId, documentId))
                  .cookie(admin())
                  .header("Origin", ORIGIN)
                  .contentType(type)
                  .content(UPLOAD_ERROR))
          .andExpect(status().isUnsupportedMediaType())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
    verify(files, never()).delete(anyString());
    assertThat(documentsOf(guestId)).isOne();
  }

  @Test
  void aStoreThatFailsKeepsTheDeletionPendingAndTheRetryCompletes() throws Exception {
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);

    delete(guestId, documentId, UPLOAD_ERROR)
        .andExpect(status().isServiceUnavailable())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
    assertThat(documentsOf(guestId)).isOne();
    assertThat(count("guest_document_actions")).isZero();
    // the store may have removed the object before failing: the deletion stays recorded and the
    // document is no longer listed, so no link is signed for an object that may be gone
    assertThat(count("guest_document_deletions")).isOne();
    mockMvc
        .perform(
            get("/bff/admin/guests/" + adminIds().mask(IdKind.GUEST, guestId) + "/documents")
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    // nor can it be replaced while its deletion is pending
    replace(admin(), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isNotFound());
    verify(files, never()).store(any(), any());

    // an object already missing counts as deleted: the store answers as for any removal
    reset(files);
    delete(guestId, documentId, UPLOAD_ERROR).andExpect(status().isNoContent());
    assertThat(documentsOf(guestId)).isZero();
    assertThat(count("guest_document_actions")).isOne();
    assertThat(count("guest_document_deletions")).isZero();

    delete(guestId, documentId, UPLOAD_ERROR)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void aTransactionThatFailsAfterTheFileLeftIsA500WithoutDetailsAndTheRetryCompletes()
      throws Exception {
    doThrow(new QueryTimeoutException("SELECT secret " + OLD_KEY))
        .when(records)
        .removeDocument(any());

    MvcResult failed =
        delete(guestId, documentId, UPLOAD_ERROR)
            .andExpect(status().isInternalServerError())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn();
    assertThat(failed.getResponse().getContentAsString())
        .doesNotContain("SELECT", "secret", OLD_KEY, "Exception");
    assertThat(documentsOf(guestId)).isOne();
    assertThat(count("guest_document_actions")).isZero();
    // who asked and when is kept, so neither the evidence nor the deletion is lost
    assertThat(count("guest_document_deletions")).isOne();

    reset(records);
    delete(guestId, documentId, UPLOAD_ERROR).andExpect(status().isNoContent());
    assertThat(documentsOf(guestId)).isZero();
    assertThat(count("guest_document_actions")).isOne();
    assertThat(count("guest_document_deletions")).isZero();
  }

  @Test
  void aDeletionNobodyRepeatsIsFinishedByThePurgerWithTheEvidenceOfWhoAsked(CapturedOutput output)
      throws Exception {
    doThrow(new QueryTimeoutException("db")).when(records).removeDocument(any());
    delete(
            guestId,
            documentId,
            "{\"reason\":\"ARCO_REQUEST\",\"requestedAt\":\"2026-01-15\","
                + "\"requestChannel\":\"EMAIL\"}")
        .andExpect(status().isInternalServerError());
    reset(records);
    // a deletion younger than a minute may still be running; this one is older
    jdbc.update(
        "UPDATE guest_document_deletions SET created_date = created_date - interval '2 minutes'");

    purger.purge(GuestDocumentPurger.AFTER_AN_ACTION);

    assertThat(documentsOf(guestId)).isZero();
    assertThat(count("guest_document_deletions")).isZero();
    actions(guestId)
        .andExpect(jsonPath("$.content[0].action").value("DELETE"))
        .andExpect(jsonPath("$.content[0].reason").value("ARCO_REQUEST"))
        .andExpect(jsonPath("$.content[0].requestChannel").value("EMAIL"))
        .andExpect(
            jsonPath("$.content[0].actorId")
                .value(adminIds().mask(IdKind.USER, adminId).toString()));
    assertThat(output).contains("did not finish").doesNotContain(DNI, NAME, OLD_KEY);
  }

  @Test
  void aRoundFinishesOnlyWhatIsOlderThanARequestInCourse() {
    for (String key : new String[] {"guest-documents/old-left", "guest-documents/new-left"}) {
      jdbc.update(
          "INSERT INTO guest_document_purges (id, storage_key) VALUES (?, ?)", TestIds.next(), key);
    }
    jdbc.update(
        "UPDATE guest_document_purges SET created_date = created_date - interval '2 minutes'"
            + " WHERE storage_key = 'guest-documents/old-left'");

    purger.purge(GuestDocumentPurger.AFTER_AN_ACTION);

    verify(files).delete("guest-documents/old-left");
    verify(files, never()).delete("guest-documents/new-left");
    assertThat(jdbc.queryForObject("SELECT storage_key FROM guest_document_purges", String.class))
        .isEqualTo("guest-documents/new-left");
  }

  @Test
  void theEvidenceCannotBeChangedNorRemoved() throws Exception {
    delete(guestId, documentId, UPLOAD_ERROR).andExpect(status().isNoContent());

    assertThatThrownBy(() -> jdbc.update("UPDATE guest_document_actions SET note = 'x'"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM guest_document_actions"))
        .isInstanceOf(DataAccessException.class);
  }

  // ------------------------------------------------------------------ replacement

  @Test
  void aReplacementPutsANewDocumentInPlaceAndRemovesTheOldFileAfterTheCommit(CapturedOutput output)
      throws Exception {
    MvcResult replaced =
        replace(
                admin(),
                guestId,
                documentId,
                PDF,
                Map.of(
                    "reason", "ARCO_REQUEST",
                    "requestedAt", "2026-01-15",
                    "requestChannel", "IN_PERSON",
                    "note", ""))
            .andExpect(status().isOk())
            .andExpect(answersTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.url").value(SIGNED))
            .andExpect(jsonPath("$.contentType").value("application/pdf"))
            .andReturn();
    String newId = JsonPath.read(replaced.getResponse().getContentAsString(), "$.id");

    assertThat(newId).isNotEqualTo(masked(documentId));
    verify(files).store(PDF, FileKind.PDF);
    verify(files).delete(OLD_KEY);
    verify(files, never()).delete(NEW_KEY);
    assertThat(count("guest_document_purges")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT storage_key FROM guest_documents WHERE guest_id = ?",
                String.class,
                guestId))
        .isEqualTo(NEW_KEY);
    actions(guestId)
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].action").value("REPLACE"))
        .andExpect(jsonPath("$.content[0].documentId").value(masked(documentId)))
        .andExpect(jsonPath("$.content[0].replacementDocumentId").value(newId))
        .andExpect(jsonPath("$.content[0].note").isEmpty())
        .andExpect(jsonPath("$.content[0].sizeBytes").value(10));
    assertThat(output).doesNotContain(DNI, NAME, OLD_KEY, NEW_KEY, SIGNED, FILE_NAME);
  }

  @Test
  void anOldFileThatCouldNotBeRemovedStaysPendingAndTheNextActionRemovesIt() throws Exception {
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);

    MvcResult replaced =
        replace(admin(), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(jdbc.queryForObject("SELECT storage_key FROM guest_document_purges", String.class))
        .isEqualTo(OLD_KEY);

    // the store is back: the next committed action retries what was left pending, once it is
    // older than a request in course
    jdbc.update(
        "UPDATE guest_document_purges SET created_date = created_date - interval '2 minutes'");
    reset(files);
    Long replacement =
        adminIds()
            .unmask(
                IdKind.GUEST_DOCUMENT,
                UUID.fromString(
                    JsonPath.read(replaced.getResponse().getContentAsString(), "$.id")));
    delete(guestId, replacement, UPLOAD_ERROR).andExpect(status().isNoContent());
    verify(files).delete(NEW_KEY);
    verify(files).delete(OLD_KEY);
    assertThat(count("guest_document_purges")).isZero();
  }

  @Test
  void aFileThatIsNotADocumentOrAnUnknownPartIsAnInvalidFile() throws Exception {
    replace(admin(), guestId, documentId, new byte[] {1, 2, 3}, Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));
    replace(admin(), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR", "extra", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));
    replace(
            admin(),
            guestId,
            documentId,
            TestPdfs.ofSize(6 * 1024 * 1024),
            Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, replacement(guestId, documentId))
                .param("reason", "UPLOAD_ERROR")
                .header("Origin", ORIGIN)
                .cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_INVALID_FILE"));
    replace(
            admin(),
            guestId,
            documentId,
            PDF,
            Map.of("reason", "ARCO_REQUEST", "requestedAt", "", "requestChannel", "EMAIL"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("requestedAt"));
    verify(files, never()).store(any(), any());
    assertThat(count("guest_document_actions")).isZero();
  }

  @Test
  void aReplacementWithoutOriginOrOfAnotherGuestsDocumentStoresNothing() throws Exception {
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, replacement(guestId, documentId))
                .file(new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF))
                .param("reason", "UPLOAD_ERROR")
                .cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    replace(admin(), guestId, otherDocumentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isNotFound())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    verify(files, never()).store(any(), any());
    assertThat(documentsOf(otherGuestId)).isOne();
  }

  @Test
  void aStoreThatFailsOnTheNewFileChangesNothing() throws Exception {
    when(files.store(any(), any())).thenThrow(new GuestDocumentStorageException("down"));

    replace(admin(), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
    assertThat(
            jdbc.queryForObject(
                "SELECT storage_key FROM guest_documents WHERE guest_id = ?",
                String.class,
                guestId))
        .isEqualTo(OLD_KEY);
    assertThat(count("guest_document_actions")).isZero();
    verify(files, never()).delete(anyString());
  }

  @Test
  void aFileThatCannotBeInspectedInTimeIsTooManyRequests() throws Exception {
    PdfInspections.whileBusy(
        () ->
            replace(admin(), guestId, documentId, PDF, Map.of("reason", "UPLOAD_ERROR"))
                .andExpect(status().isTooManyRequests())
                .andExpect(answersTheContract())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED")));
    verify(files, never()).store(any(), any());
  }

  // ------------------------------------------------------------------ evidence

  /**
   * TAR-227: the cursor is opaque and belongs to the session that got it. It carries neither the
   * internal id nor the date; a cursor of another session, an altered one, or one in the old form
   * (with the internal id) is an invalid cursor.
   */
  @Test
  void aCursorIsValidOnlyInTheSessionThatGotIt() throws Exception {
    Long second = insertDocument(guestId, "guest-documents/second");
    delete(guestId, documentId, UPLOAD_ERROR).andExpect(status().isNoContent());
    delete(guestId, second, UPLOAD_ERROR).andExpect(status().isNoContent());
    MvcResult first =
        mockMvc
            .perform(get(actionsRoute(guestId)).param("limit", "1").cookie(admin()))
            .andExpect(status().isOk())
            .andReturn();
    String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    Long lastShown =
        jdbc.queryForObject(
            "SELECT id FROM guest_document_actions WHERE document_id = ?", Long.class, second);
    String key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    // Only a UUID of this session: neither the internal id nor the date.
    assertThat(key).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    UUID shown = UUID.fromString(key);
    assertThat(adminIds().unmask(IdKind.GUEST_DOCUMENT_ACTION, shown)).isEqualTo(lastShown);

    Cookie other = session(adminId);
    String otherRoute =
        "/bff/admin/guests/"
            + SessionIds.of(masker, other).mask(IdKind.GUEST, guestId)
            + "/document-actions";
    String oldForm = encoded("2026-01-01T00:00:00Z") + "." + encoded(String.valueOf(lastShown));
    String altered =
        encoded(
            new UUID(shown.getMostSignificantBits() ^ 1L, shown.getLeastSignificantBits())
                .toString());
    for (String refused :
        new String[] {cursor, oldForm, encoded(String.valueOf(lastShown)), altered}) {
      boolean ofTheFirstSession = refused.equals(cursor);
      mockMvc
          .perform(
              get(ofTheFirstSession ? otherRoute : actionsRoute(guestId))
                  .param("limit", "1")
                  .param("after", refused)
                  .cookie(ofTheFirstSession ? other : admin()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("after"));
    }
    MvcResult otherFirst =
        mockMvc
            .perform(get(otherRoute).param("limit", "1").cookie(other))
            .andExpect(status().isOk())
            .andReturn();
    String otherCursor =
        JsonPath.read(otherFirst.getResponse().getContentAsString(), "$.nextCursor");
    // The same position, but another session: another cursor, which pages on in its own session.
    assertThat(otherCursor).isNotEqualTo(cursor);
    mockMvc
        .perform(get(otherRoute).param("limit", "1").param("after", otherCursor).cookie(other))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.content[0].documentId")
                .value(
                    SessionIds.of(masker, other)
                        .mask(IdKind.GUEST_DOCUMENT, documentId)
                        .toString()));
  }

  private static String encoded(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void theEvidenceIsPagedNewestFirstAndAnUnknownGuestIsNotFound() throws Exception {
    Long second = insertDocument(guestId, "guest-documents/second");
    delete(guestId, documentId, UPLOAD_ERROR).andExpect(status().isNoContent());
    delete(guestId, second, UPLOAD_ERROR).andExpect(status().isNoContent());

    MvcResult first =
        mockMvc
            .perform(get(actionsRoute(guestId)).param("limit", "1").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content[0].documentId").value(masked(second)))
            .andReturn();
    String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    mockMvc
        .perform(
            get(actionsRoute(guestId)).param("limit", "1").param("after", cursor).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].documentId").value(masked(documentId)))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
    mockMvc
        .perform(get(actionsRoute(guestId)).param("after", "not-a-cursor").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("after"));
    mockMvc
        .perform(get(actionsRoute(otherGuestId)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isEmpty());
    mockMvc
        .perform(
            get("/bff/admin/guests/" + UUID.randomUUID() + "/document-actions").cookie(admin()))
        .andExpect(status().isNotFound())
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

  private String masked(Long document) {
    return adminIds().mask(IdKind.GUEST_DOCUMENT, document).toString();
  }

  private String replacement(Long guest, Long document) {
    return "/bff/admin/guests/"
        + adminIds().mask(IdKind.GUEST, guest)
        + "/documents/"
        + masked(document);
  }

  private String deletion(Long guest, Long document) {
    return replacement(guest, document) + "/deletion";
  }

  private String actionsRoute(Long guest) {
    return "/bff/admin/guests/" + adminIds().mask(IdKind.GUEST, guest) + "/document-actions";
  }

  private ResultActions delete(Long guest, Long document, String body) throws Exception {
    return mockMvc.perform(
        post(deletion(guest, document))
            .cookie(admin())
            .header("Origin", ORIGIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions replace(
      Cookie who, Long guest, Long document, byte[] content, Map<String, String> fields)
      throws Exception {
    MockMultipartHttpServletRequestBuilder request =
        multipart(HttpMethod.PUT, replacement(guest, document))
            .file(new MockMultipartFile("file", FILE_NAME, "application/pdf", content));
    fields.forEach(request::param);
    return mockMvc.perform(request.header("Origin", ORIGIN).cookie(who));
  }

  private ResultActions actions(Long guest) throws Exception {
    return mockMvc.perform(get(actionsRoute(guest)).cookie(admin()));
  }

  /** One session per test (TAR-227: its masked ids are valid only in it). */
  private Cookie admin() {
    Cookie session = admin;
    if (session == null) {
      session = session(adminId);
      admin = session;
    }
    return session;
  }

  /** The ids as the session of {@link #admin} shows them. */
  private MaskSession adminIds() {
    return SessionIds.of(masker, admin());
  }

  private Cookie session(Long accountId) {
    return new Cookie("sessionToken", jwtTokenProvider.issue(accountId.toString()));
  }

  private int documentsOf(Long guest) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM guest_documents WHERE guest_id = ?", Integer.class, guest);
    return count == null ? 0 : count;
  }

  private int count(String table) {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return count == null ? 0 : count;
  }

  private void insertAccount(Long id, String role, String document, @Nullable Long guest) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', ?, 'hash', ?, ?, FALSE, 'ACTIVE')",
        id,
        document,
        role,
        guest);
  }

  private Long insertGuest(String number) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, full_name, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, 'CONTRACT', 'ACTIVE')",
        id,
        number,
        NAME);
    return id;
  }

  private Long insertDocument(Long guest, String key) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, ?, ?, 'application/pdf', 10)",
        id,
        guest,
        key,
        FILE_NAME);
    return id;
  }
}
