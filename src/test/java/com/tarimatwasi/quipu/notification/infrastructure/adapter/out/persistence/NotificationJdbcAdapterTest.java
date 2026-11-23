package com.tarimatwasi.quipu.notification.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.notification.application.port.out.NotificationRepositoryPort.Inbox;
import com.tarimatwasi.quipu.notification.domain.Audience;
import com.tarimatwasi.quipu.notification.domain.Notification;
import com.tarimatwasi.quipu.notification.domain.NotificationType;
import com.tarimatwasi.quipu.notification.domain.TargetKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The SQL of the bell: inboxes, cursors, read state per reader and the purge by age. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class NotificationJdbcAdapterTest {

  private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

  /** Masking needs the session of a request; a fixed one stands for it. */
  private static final IdMasker MASKER =
      new IdMasker(
          new IdMaskProperties("a-test-key-with-more-than-thirty-two-characters"),
          () -> Optional.of(SessionHolder.SESSION));

  @Autowired JdbcTemplate jdbc;
  @Autowired JdbcClient client;
  @Autowired JsonMapper json;

  private NotificationJdbcAdapter adapter;

  private final Long guest = TestIds.next();
  private final Long otherGuest = TestIds.next();
  private final Inbox admins = new Inbox(Audience.ADMINISTRATORS, null, TestIds.next());
  private final Inbox otherAdmin = new Inbox(Audience.ADMINISTRATORS, null, TestIds.next());
  private final Inbox guestInbox = new Inbox(Audience.GUEST, guest, TestIds.next());

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    adapter = new NotificationJdbcAdapter(client, json, MASKER);
  }

  /** Created after MASKER, from it. */
  private static final class SessionHolder {
    static final MaskSession SESSION = MASKER.session("AAECAwQFBgcICQoLDA0ODw");
  }

  @Test
  void theSameEventRaisesOneNotificationOfAType() {
    Notification raised = admin(T0);

    assertThat(adapter.insert(raised)).isPresent();
    assertThat(adapter.insert(raised)).isEmpty();
    assertThat(adapter.unreadCount(admins)).isEqualTo(1);
  }

  /** A notification of the administrators may be about no guest at all. */
  @Test
  void aNotificationOfTheAdministratorsMayNameNoGuest() {
    adapter.insert(
        Notification.raised(
            UUID.randomUUID(),
            NotificationType.ONBOARDING_COMPLETED,
            null,
            TargetKind.GUEST,
            TestIds.next(),
            Map.of(),
            T0));

    assertThat(adapter.find(admins, false, PageQuery.first()).content())
        .singleElement()
        .satisfies(notification -> assertThat(notification.guestId()).isNull());
  }

  @Test
  void pagesWalkTheInboxNewestFirstWithoutGapsOrRepeats() {
    List<Long> inserted = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      inserted.add(adapter.insert(admin(T0.plusSeconds(i))).orElseThrow());
    }
    // Two at the same instant: the id breaks the tie.
    inserted.add(adapter.insert(admin(T0.plusSeconds(4))).orElseThrow());

    List<Long> seen = new ArrayList<>();
    String after = null;
    do {
      CursorPage<Notification> page = adapter.find(admins, false, new PageQuery(after, 2));
      seen.addAll(ids(page));
      after = page.nextCursor();
    } while (after != null);

    assertThat(seen).containsExactlyElementsOf(inserted.reversed());
  }

  @Test
  void aGuestReadsOnlyItsOwnInboxAndTheAdministratorsNone() {
    Long mine = adapter.insert(forGuest(guest, T0)).orElseThrow();
    adapter.insert(forGuest(otherGuest, T0));
    adapter.insert(admin(T0));

    assertThat(ids(adapter.find(guestInbox, false, PageQuery.first()))).containsExactly(mine);
    assertThat(adapter.unreadCount(guestInbox)).isEqualTo(1);
    assertThat(adapter.markRead(admins, mine, T0)).isFalse();
    assertThat(adapter.unreadCount(guestInbox)).isEqualTo(1);
  }

  @Test
  void eachAdministratorHasItsOwnReadState() {
    Long first = adapter.insert(admin(T0)).orElseThrow();
    Long second = adapter.insert(admin(T0.plusSeconds(1))).orElseThrow();

    assertThat(adapter.markRead(admins, first, T0)).isTrue();
    assertThat(adapter.markRead(admins, first, T0.plusSeconds(9))).isTrue();

    assertThat(ids(adapter.find(admins, true, PageQuery.first()))).containsExactly(second);
    assertThat(adapter.unreadCount(otherAdmin)).isEqualTo(2);
    adapter.markAllRead(admins, T0);
    assertThat(adapter.unreadCount(admins)).isZero();
    assertThat(adapter.unreadCount(otherAdmin)).isEqualTo(2);
    assertThat(adapter.find(admins, false, PageQuery.first()).content())
        .allSatisfy(notification -> assertThat(notification.readAt()).isEqualTo(T0));
  }

  @Test
  void aCursorOfAnotherInboxIsInvalid() {
    adapter.insert(forGuest(otherGuest, T0));
    adapter.insert(forGuest(otherGuest, T0.plusSeconds(1)));
    String cursor =
        Objects.requireNonNull(
            adapter
                .find(
                    new Inbox(Audience.GUEST, otherGuest, TestIds.next()),
                    false,
                    new PageQuery(null, 1))
                .nextCursor());

    assertThatThrownBy(() -> adapter.find(guestInbox, false, new PageQuery(cursor, 1)))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void thePurgeRemovesTheOldestFirstAndAtMostTheMaximum() {
    Long oldest = adapter.insert(admin(T0)).orElseThrow();
    Long old = adapter.insert(admin(T0.plusSeconds(1))).orElseThrow();
    Long kept = adapter.insert(admin(T0.plusSeconds(60))).orElseThrow();
    adapter.markRead(admins, oldest, T0);

    assertThat(adapter.deleteCreatedBefore(T0.plusSeconds(30), 1)).isEqualTo(1);
    assertThat(ids(adapter.find(admins, false, PageQuery.first()))).containsExactly(kept, old);
    assertThat(adapter.deleteCreatedBefore(T0.plusSeconds(30), 10)).isEqualTo(1);
    assertThat(ids(adapter.find(admins, false, PageQuery.first()))).containsExactly(kept);
  }

  private static List<Long> ids(CursorPage<Notification> page) {
    return page.content().stream()
        .map(notification -> Objects.requireNonNull(notification.id()))
        .toList();
  }

  private static Notification admin(Instant at) {
    return Notification.raised(
        UUID.randomUUID(),
        NotificationType.ONBOARDING_COMPLETED,
        TestIds.next(),
        TargetKind.GUEST,
        TestIds.next(),
        Map.of(),
        at);
  }

  private static Notification forGuest(Long guestId, Instant at) {
    return Notification.raised(
        UUID.randomUUID(),
        NotificationType.REFUND_CREATED,
        guestId,
        TargetKind.REFUND,
        TestIds.next(),
        Map.of("amount", "10.00"),
        at);
  }
}
