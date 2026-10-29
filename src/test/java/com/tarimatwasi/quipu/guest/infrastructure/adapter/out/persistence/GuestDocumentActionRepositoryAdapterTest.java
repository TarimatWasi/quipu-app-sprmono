package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentDeletionPendingException;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.NoMaskSessionException;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

/** TAR-187 (RN-37): the evidence of the actions and the objects left to remove. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class GuestDocumentActionRepositoryAdapterTest {

  private static final Why ARCO =
      new Why(Reason.ARCO_REQUEST, LocalDate.parse("2026-01-15"), Channel.OTHER, "ticket");

  @Autowired GuestDocumentActionRepositoryAdapter adapter;
  @Autowired OnboardingRepositoryPort onboarding;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired JwtDecoder jwtDecoder;
  @Autowired AccountJwtAuthenticationConverter sessions;
  @Autowired IdMasker masker;

  private final Long admin = TestIds.next();
  private final Long guest = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000001', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        admin);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guest);
    signIn();
  }

  /**
   * TAR-227: a new session of the administrator, as the security chain builds it from its token
   * (the cursors are masked with the key of the session).
   */
  private void signIn() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            sessions.convert(jwtDecoder.decode(jwtTokenProvider.issue(admin.toString()))));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    TestTables.clear(jdbc);
  }

  private GuestDocument document(Long id) {
    return new GuestDocument(
        id, guest, "guest-documents/" + id, "d.pdf", "image/png", 42, Instant.now());
  }

  private GuestDocumentAction add(Long documentId) {
    return Objects.requireNonNull(
        transaction.execute(
            status ->
                adapter.addAction(
                    Kind.DELETE, document(documentId), null, ARCO, admin, Instant.now())));
  }

  @Test
  void anActionKeepsWhoAndWhyAndTheTypeAndSizeOfTheFile() {
    GuestDocumentAction action = add(1L);

    assertThat(action.actorId()).isEqualTo(admin);
    assertThat(action.why()).isEqualTo(ARCO);
    assertThat(action.contentType()).isEqualTo("image/png");
    assertThat(action.sizeBytes()).isEqualTo(42);
    assertThat(action.performedAt()).isNotNull();
  }

  @Test
  void aSecondActionOnTheDocumentIsReportedAsSuch() {
    add(1L);

    assertThatThrownBy(() -> add(1L)).isInstanceOf(GuestDocumentActionExistsException.class);
  }

  @Test
  void anActionBreakingAnotherRuleKeepsTheFailure() {
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        adapter.addAction(
                            Kind.REPLACE,
                            document(1L),
                            null,
                            new Why(Reason.UPLOAD_ERROR, null, null, null),
                            admin,
                            Instant.now())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void thePagesGoNewestFirstAndAForgedCursorIsRefused() {
    add(1L);
    add(2L);
    add(3L);

    var first = adapter.actionsOf(guest, new PageQuery(null, 2));
    assertThat(first.content()).extracting(GuestDocumentAction::documentId).containsExactly(3L, 2L);
    var second = adapter.actionsOf(guest, new PageQuery(first.nextCursor(), 2));
    assertThat(second.content()).extracting(GuestDocumentAction::documentId).containsExactly(1L);
    assertThat(second.nextCursor()).isNull();

    Long last =
        jdbc.queryForObject(
            "SELECT id FROM guest_document_actions WHERE document_id = 2", Long.class);
    for (String forged :
        new String[] {
          CursorToken.encode("yesterday", "1"),
          CursorToken.encode(Instant.now().toString(), "x"),
          // TAR-227: the cursor of before, with the internal id, is no longer one
          CursorToken.encode(Instant.now().toString(), String.valueOf(last)),
          CursorToken.encode(String.valueOf(last)),
          CursorToken.encode(UUID.randomUUID().toString()),
          "a"
        }) {
      assertThatThrownBy(() -> adapter.actionsOf(guest, new PageQuery(forged, 2)))
          .isInstanceOf(InvalidCursorException.class);
    }
  }

  /** TAR-227: the cursor hides the internal id and the date, and serves only its own session. */
  @Test
  void aCursorIsOpaqueAndValidOnlyInItsSessionAndForItsGuest() {
    add(1L);
    add(2L);
    add(3L);
    var first = adapter.actionsOf(guest, new PageQuery(null, 2));
    String cursor = Objects.requireNonNull(first.nextCursor());
    Long last =
        jdbc.queryForObject(
            "SELECT id FROM guest_document_actions WHERE document_id = 2", Long.class);

    String key = CursorToken.decode(cursor, 1).get(0);
    assertThat(key).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    assertThat(masker.unmask(IdKind.GUEST_DOCUMENT_ACTION, UUID.fromString(key))).isEqualTo(last);
    assertThat(adapter.actionsOf(guest, new PageQuery(cursor, 2)).content())
        .extracting(GuestDocumentAction::documentId)
        .containsExactly(1L);

    // the same position, read by another guest, is not a cursor of that guest
    Long otherGuest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '87654321', 'CONTRACT', 'ACTIVE')",
        otherGuest);
    assertThatThrownBy(() -> adapter.actionsOf(otherGuest, new PageQuery(cursor, 2)))
        .isInstanceOf(InvalidCursorException.class);

    // another session of the same administrator
    signIn();
    assertThatThrownBy(() -> adapter.actionsOf(guest, new PageQuery(cursor, 2)))
        .isInstanceOf(InvalidCursorException.class);
    String again =
        Objects.requireNonNull(adapter.actionsOf(guest, new PageQuery(null, 2)).nextCursor());
    assertThat(again).isNotEqualTo(cursor);
    assertThat(adapter.actionsOf(guest, new PageQuery(again, 2)).content())
        .extracting(GuestDocumentAction::documentId)
        .containsExactly(1L);

    // with no session at all it fails closed
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> adapter.actionsOf(guest, new PageQuery(null, 2)))
        .isInstanceOf(NoMaskSessionException.class);
  }

  @Test
  void theObjectsLeftToRemoveAreKeptUntilTheyAreForgotten() {
    transaction.executeWithoutResult(status -> adapter.addPurge("guest-documents/a"));
    transaction.executeWithoutResult(status -> adapter.addPurge("guest-documents/b"));

    Instant later = Instant.now().plusSeconds(60);
    assertThat(adapter.pendingPurges(later, 10))
        .containsExactly("guest-documents/a", "guest-documents/b");
    transaction.executeWithoutResult(status -> adapter.removePurge("guest-documents/a"));
    transaction.executeWithoutResult(status -> adapter.removePurge("guest-documents/unknown"));
    assertThat(adapter.pendingPurges(later, 10)).containsExactly("guest-documents/b");
  }

  /**
   * The real queries, with the zone of this JVM (not UTC on the machines of the team): only what is
   * older than the margin of a request in course is returned.
   */
  @Test
  void onlyWhatIsOlderThanTheMarginIsPending() {
    Instant asked = Instant.now();
    transaction.executeWithoutResult(
        status -> {
          adapter.addPurge("guest-documents/old");
          adapter.addPurge("guest-documents/new");
          adapter.addPendingDeletion(
              new PendingDeletion(guest, 5L, ARCO, "image/png", 42, admin, asked));
          adapter.addPendingDeletion(
              new PendingDeletion(guest, 6L, ARCO, "image/png", 42, admin, asked));
        });
    jdbc.update(
        "UPDATE guest_document_purges SET created_date = now() - interval '2 minutes'"
            + " WHERE storage_key = 'guest-documents/old'");
    jdbc.update(
        "UPDATE guest_document_deletions SET created_date = now() - interval '2 minutes'"
            + " WHERE document_id = 5");
    Instant margin = Instant.now().minus(Duration.ofMinutes(1));

    assertThat(adapter.pendingPurges(margin, 10)).containsExactly("guest-documents/old");
    assertThat(adapter.pendingDeletions(margin, 10))
        .extracting(PendingDeletion::documentId)
        .containsExactly(5L);
  }

  @Test
  void theActorAndTheTimeOfTheEvidenceAreTheOnesGivenNotTheSession() {
    Long other = TestIds.next();
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, NULL, 'DNI', '00000002', 'hash', 'ADMIN', FALSE, 'ACTIVE')",
        other);
    Instant asked = Instant.parse("2026-10-01T12:00:00Z");

    GuestDocumentAction action =
        Objects.requireNonNull(
            transaction.execute(
                status -> adapter.addAction(Kind.DELETE, document(9L), null, ARCO, other, asked)));

    assertThat(action.actorId()).isEqualTo(other);
    assertThat(action.performedAt()).isEqualTo(asked);
  }

  @Test
  void aPendingDeletionIsKeptUntilItIsForgotten() {
    Instant asked = Instant.parse("2026-10-01T12:00:00Z");
    PendingDeletion pending = new PendingDeletion(guest, 5L, ARCO, "image/png", 42, admin, asked);
    transaction.executeWithoutResult(status -> adapter.addPendingDeletion(pending));

    assertThat(adapter.pendingDeletionOf(5L)).contains(pending);
    assertThat(adapter.pendingDeletions(asked.plusSeconds(1), 10)).containsExactly(pending);
    assertThat(adapter.pendingDeletions(asked, 10)).isEmpty();
    transaction.executeWithoutResult(status -> adapter.removePendingDeletion(5L));
    transaction.executeWithoutResult(status -> adapter.removePendingDeletion(5L));
    assertThat(adapter.pendingDeletionOf(5L)).isEmpty();
  }

  /** TAR-234: only the rule of one pending deletion per document is the race of two deletions. */
  @Test
  void aSecondPendingDeletionOfTheDocumentIsReportedAsSuch() {
    PendingDeletion pending =
        new PendingDeletion(guest, 5L, ARCO, "image/png", 42, admin, Instant.now());
    transaction.executeWithoutResult(status -> adapter.addPendingDeletion(pending));

    assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> adapter.addPendingDeletion(pending)))
        .isInstanceOf(GuestDocumentDeletionPendingException.class);
    var unknown = new DataIntegrityViolationException("x");
    assertThat(GuestDocumentActionRepositoryAdapter.pendingConflict(unknown)).isSameAs(unknown);
  }

  @Test
  void aDocumentBeingDeletedIsNeitherListedNorCountedForItsGuest() {
    Long kept = TestIds.next();
    Long leaving = TestIds.next();
    for (Long id : new Long[] {kept, leaving}) {
      jdbc.update(
          "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
              + " size_bytes) VALUES (?, ?, ?, 'd.pdf', 'application/pdf', 10)",
          id,
          guest,
          "guest-documents/" + id);
    }
    transaction.executeWithoutResult(
        status ->
            adapter.addPendingDeletion(
                new PendingDeletion(guest, leaving, ARCO, "image/png", 42, admin, Instant.now())));

    assertThat(onboarding.documentsOf(guest)).extracting(GuestDocument::id).containsExactly(kept);
    assertThat(onboarding.countDocuments(guest)).isOne();
  }

  @Test
  void aDocumentIsFoundOnlyUnderItsOwnGuest() {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, 'guest-documents/mine', 'd.pdf', 'application/pdf', 10)",
        id,
        guest);

    assertThat(adapter.findDocument(guest, id))
        .map(GuestDocument::storageKey)
        .contains("guest-documents/mine");
    assertThat(adapter.findDocument(guest + 1, id)).isEmpty();
  }

  @Test
  void aKeyIsReferencedWhileARowUsesIt() {
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, 'guest-documents/used', 'd.pdf', 'application/pdf', 10)",
        TestIds.next(),
        guest);

    assertThat(adapter.isReferenced("guest-documents/used")).isTrue();
    assertThat(adapter.isReferenced("guest-documents/free")).isFalse();
  }
}
