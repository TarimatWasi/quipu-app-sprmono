package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * TAR-227: the cursor of the evidence of the document actions is opaque and belongs to the session
 * that got it. It carries neither the internal id nor the date; a cursor of another session, an
 * altered one, or one in the old form (with the internal id) is an invalid cursor, with the same
 * answer as any other invalid cursor. The bucket is a fake of the port.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class GuestDocumentActionsSessionContractTest {

  private static final String ORIGIN = "http://localhost:3000";
  private static final String UPLOAD_ERROR = "{\"reason\":\"UPLOAD_ERROR\"}";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean GuestDocumentFilePort files;

  private final Long adminId = TestIds.next();
  private Long guestId;
  private Long olderDocument;
  private Long newerDocument;

  /** Two sessions of the same administrator, each opened by its own sign-in. */
  private Cookie first;

  private Cookie second;

  /** The cursor the first session got after the newest action, and the action it stands at. */
  private String cursor;

  private Long lastShown;

  @BeforeEach
  void twoActionsAndAFirstPage() throws Exception {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        adminId);
    guestId = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, full_name, guest_type, status)"
            + " VALUES (?, 'DNI', '45678912', 'Rosa Mamani', 'CONTRACT', 'ACTIVE')",
        guestId);
    olderDocument = insertDocument("guest-documents/older");
    newerDocument = insertDocument("guest-documents/newer");
    first = new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
    second = new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
    delete(first, olderDocument);
    delete(first, newerDocument);
    cursor = nextCursorOf(first, null);
    lastShown =
        jdbc.queryForObject(
            "SELECT id FROM guest_document_actions WHERE document_id = ?",
            Long.class,
            newerDocument);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void theCursorIsOnlyTheIdOfTheLastActionMaskedInItsSession() {
    String key = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);

    // Only a UUID of this session: neither the internal id nor the date.
    assertThat(key).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    assertThat(ids(first).unmask(IdKind.GUEST_DOCUMENT_ACTION, UUID.fromString(key)))
        .isEqualTo(lastShown);
  }

  @Test
  void theCursorPagesOnInTheSessionThatGotIt() throws Exception {
    mockMvc
        .perform(page(first, cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].documentId").value(masked(first, olderDocument)))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
  }

  @Test
  void theCursorOfAnotherSessionIsAnInvalidCursor() throws Exception {
    assertInvalidCursor(second, cursor);

    // The same position seen by the other session has a cursor of its own, which pages on there.
    String own = nextCursorOf(second, null);
    assertThat(own).isNotEqualTo(cursor);
    mockMvc
        .perform(page(second, own))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].documentId").value(masked(second, olderDocument)));
  }

  @Test
  void anAlteredCursorOrOneInTheOldFormIsAnInvalidCursor() throws Exception {
    UUID shown =
        UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
    String altered =
        encoded(
            new UUID(shown.getMostSignificantBits() ^ 1L, shown.getLeastSignificantBits())
                .toString());
    String oldForm = encoded("2026-01-01T00:00:00Z") + "." + encoded(String.valueOf(lastShown));

    for (String refused : new String[] {altered, oldForm, encoded(String.valueOf(lastShown))}) {
      assertInvalidCursor(first, refused);
    }
  }

  private void assertInvalidCursor(Cookie session, String refused) throws Exception {
    mockMvc
        .perform(page(session, refused))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("after"));
  }

  private String nextCursorOf(Cookie session, @Nullable String after) throws Exception {
    MvcResult result = mockMvc.perform(page(session, after)).andExpect(status().isOk()).andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.nextCursor");
  }

  private MockHttpServletRequestBuilder page(Cookie session, @Nullable String after) {
    var request =
        get("/bff/admin/guests/" + ids(session).mask(IdKind.GUEST, guestId) + "/document-actions")
            .param("limit", "1")
            .cookie(session);
    return after == null ? request : request.param("after", after);
  }

  private void delete(Cookie session, Long document) throws Exception {
    mockMvc
        .perform(
            post("/bff/admin/guests/"
                    + ids(session).mask(IdKind.GUEST, guestId)
                    + "/documents/"
                    + masked(session, document)
                    + "/deletion")
                .cookie(session)
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPLOAD_ERROR))
        .andExpect(status().isNoContent());
  }

  private MaskSession ids(Cookie session) {
    return SessionIds.of(masker, session);
  }

  private String masked(Cookie session, Long document) {
    return ids(session).mask(IdKind.GUEST_DOCUMENT, document).toString();
  }

  private static String encoded(String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private Long insertDocument(String key) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, ?, 'dni.pdf', 'application/pdf', 10)",
        id,
        guestId,
        key);
    return id;
  }
}
