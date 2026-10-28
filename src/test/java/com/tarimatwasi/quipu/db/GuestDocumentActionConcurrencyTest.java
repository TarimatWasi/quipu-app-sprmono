package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TAR-187 (RN-37): two actions on one document at once. Both lock the guest and then the document,
 * so the second waits and then finds the document gone: one wins, the other is a 404, and there is
 * one piece of evidence and no object of the loser left behind.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class GuestDocumentActionConcurrencyTest {

  private static final int ROUNDS = 8;
  private static final String ORIGIN = "http://localhost:3000";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean GuestDocumentFilePort files;

  private final Long adminId = TestIds.next();
  private Long guest;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any(), any())).thenAnswer(call -> "guest-documents/" + UUID.randomUUID());
    when(files.signedUrl(anyString(), any())).thenReturn("https://r2.test/s");
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'admin@example.test', 'DNI', '00000001', 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId);
    guest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void ofADeletionAndAReplacementAtOnceOnlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Long document = insertDocument();
        List<Integer> answers = race(pool, () -> delete(document), () -> replace(document));

        assertThat(answers).containsAnyOf(204, 200).contains(404);
        assertThat(actionsOn(document)).isOne();
        // the winner leaves one row: none after a deletion, the new one after a replacement
        assertThat(documents()).isEqualTo(answers.get(0) == 204 ? 0 : 1);
        jdbc.update(
            "TRUNCATE TABLE guest_document_actions, guest_document_purges, guest_documents");
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ofTwoDeletionsAtOnceOnlyOneWins() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        Long document = insertDocument();
        List<Integer> answers = race(pool, () -> delete(document), () -> delete(document));

        assertThat(answers).containsExactlyInAnyOrder(204, 404);
        assertThat(actionsOn(document)).isOne();
        assertThat(documents()).isZero();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static List<Integer> race(
      ExecutorService pool, Callable<Integer> first, Callable<Integer> second) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Future<Integer> one =
        pool.submit(
            () -> {
              start.await();
              return first.call();
            });
    Future<Integer> two =
        pool.submit(
            () -> {
              start.await();
              return second.call();
            });
    start.countDown();
    return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
  }

  private int delete(Long document) throws Exception {
    return mockMvc
        .perform(
            post(route(document) + "/deletion")
                .cookie(admin())
                .header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"UPLOAD_ERROR\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private int replace(Long document) throws Exception {
    return mockMvc
        .perform(
            multipart(HttpMethod.PUT, route(document))
                .file(new MockMultipartFile("file", "d.pdf", "application/pdf", TestPdfs.plain()))
                .param("reason", "UPLOAD_ERROR")
                .header("Origin", ORIGIN)
                .cookie(admin()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String route(Long document) {
    return "/bff/admin/guests/"
        + masker.mask(IdKind.GUEST, guest)
        + "/documents/"
        + masker.mask(IdKind.GUEST_DOCUMENT, document);
  }

  private Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  private Long insertDocument() {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, ?, 'd.pdf', 'application/pdf', 10)",
        id,
        guest,
        "guest-documents/" + UUID.randomUUID());
    return id;
  }

  private int actionsOn(Long document) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM guest_document_actions WHERE document_id = ?",
            Integer.class,
            document);
    return count == null ? 0 : count;
  }

  private int documents() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM guest_documents", Integer.class);
    return count == null ? 0 : count;
  }
}
