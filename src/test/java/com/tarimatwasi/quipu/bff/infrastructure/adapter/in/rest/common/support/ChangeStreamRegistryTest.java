package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Change;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals.Kind;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMaskProperties;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.SessionIds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class ChangeStreamRegistryTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
  private final IdMasker ids =
      new IdMasker(
          new IdMaskProperties("test-only-key-of-at-least-32-characters"), Optional::empty);
  private final MaskSession mask = ids.session(SessionIds.newSessionId());
  private final ChangeStreamRegistry streams = registry(4, 64);

  private ChangeStreamRegistry registry(int perAccount, int keys) {
    return new ChangeStreamRegistry(
        new ChangeStreamProperties(
            perAccount, 64, keys, 8192, Duration.ofMillis(1), Duration.ofMinutes(15)),
        clock);
  }

  private ChangeStreamRegistry.Subscription subscribe(Long account, Long guest, boolean admin) {
    var listener =
        streams.reserve(account, "sid-" + account, 0, admin, mask, clock.instant().plusSeconds(60));
    assertThat(listener.activate(guest)).startsWith("event: ready");
    return listener;
  }

  private static void transaction(Runnable action, boolean commit) {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      action.run();
      if (commit) {
        TransactionSynchronizationUtils.invokeAfterCompletion(
            TransactionSynchronizationManager.getSynchronizations(),
            TransactionSynchronization.STATUS_COMMITTED);
      }
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @AfterEach
  void cleanup() {
    streams.shutdown();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void changed_outsideTransaction_rejects() {
    assertThatThrownBy(() -> streams.changed(new Change(Kind.PAYMENT, 1L, true, 2L, 3L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void changed_rollbackAndIdle_publishNothing() throws Exception {
    try (var guest = subscribe(2L, 1L, false)) {
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 1L, true, 2L, 3L)), false);
      assertThat(guest.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void changed_committedPayment_routesOnlyToItsAudience() throws Exception {
    try (var admin = subscribe(1L, 0L, true);
        var guest = subscribe(2L, 5L, false);
        var other = subscribe(3L, 9L, false)) {
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 5L, true, 7L, 8L)), true);
      assertThat(admin.next())
          .contains(
              "admin.payments.queue",
              "admin.dashboard",
              "admin.payment:" + mask.mask(IdKind.PAYMENT, 7L))
          .doesNotContain("guest.home");
      assertThat(guest.next())
          .contains(
              "guest.home", "guest.payments", "guest.payment:" + mask.mask(IdKind.PAYMENT, 7L))
          .doesNotContain("admin.");
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void changed_multipleSessions_usesReceivingMask() throws Exception {
    var second = ids.session(SessionIds.newSessionId());
    try (var first = subscribe(2L, 5L, false);
        var other =
            streams.reserve(2L, "other", 0, false, second, clock.instant().plusSeconds(60))) {
      other.activate(5L);
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 5L, false, 7L, null)), true);
      assertThat(first.next())
          .contains("guest.stay", "guest.payment:" + mask.mask(IdKind.PAYMENT, 7L));
      assertThat(other.next()).contains("guest.payment:" + second.mask(IdKind.PAYMENT, 7L));
    }
  }

  @Test
  void changed_keyOverflow_collapsesToAll() throws Exception {
    var limited = registry(4, 1);
    try (var listener =
        limited.reserve(1L, "sid", 0, true, mask, clock.instant().plusSeconds(60))) {
      listener.activate(null);
      transaction(() -> limited.changed(new Change(Kind.FINANCIAL, 5L, true, 7L, 8L)), true);
      assertThat(listener.next()).contains("\"invalidate\":\"ALL\"");
    }
  }

  @Test
  void changed_multipleCommits_aggregatesWithMonotonicVersions() throws Exception {
    try (var guest = subscribe(2L, 5L, false)) {
      transaction(
          () -> {
            streams.changed(new Change(Kind.PAYMENT, 5L, true, 7L, 8L));
            streams.changed(new Change(Kind.CONTRACT, 5L, true, null, 8L));
          },
          true);
      assertThat(guest.next()).contains("\"version\":1", "guest.contract", "guest.payment:");
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 9L, true, 4L, null)), true);
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 5L, true, 8L, null)), true);
      assertThat(guest.next()).contains("\"version\":3");
    }
  }

  @Test
  void activate_concurrentRevocation_rejects() {
    try (var listener =
        streams.reserve(1L, "sid", 0, true, mask, clock.instant().plusSeconds(60))) {
      transaction(() -> streams.revokeSession(1L, "sid"), true);
      assertThatThrownBy(() -> listener.activate(null))
          .isInstanceOf(InvalidBearerTokenException.class);
    }
  }

  @Test
  void credentialsChanged_oldAndFreshSessions_closesOnlyOldCredentials() throws Exception {
    try (var kept = streams.reserve(1L, "kept", 0, true, mask, clock.instant().plusSeconds(60));
        var other = streams.reserve(1L, "other", 0, true, mask, clock.instant().plusSeconds(60));
        var fresh = streams.reserve(1L, "fresh", 1, true, mask, clock.instant().plusSeconds(60))) {
      kept.activate(null);
      other.activate(null);
      fresh.activate(null);
      transaction(() -> streams.credentialsChanged(1L, 1, "kept"), true);
      assertThat(kept.next()).contains("session-updated");
      assertThat(other.next()).contains("session-revoked");
      assertThat(fresh.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void reserve_closingWriter_keepsPhysicalSlot() {
    var capped = registry(1, 64);
    try (var listener = capped.reserve(1L, "sid", 0, true, mask, clock.instant().plusSeconds(60))) {
      listener.activate(null);
      transaction(() -> capped.revokeSession(1L, "sid"), true);
      assertThatThrownBy(
              () -> capped.reserve(1L, "new", 0, true, mask, clock.instant().plusSeconds(60)))
          .isInstanceOf(ChangeStreamLimitException.class);
    }
    assertThat(capped.connectionCount()).isZero();
    capped.reserve(1L, "new", 0, true, mask, clock.instant().plusSeconds(60)).close();
  }

  @Test
  void next_expiryAndShutdown_prioritizesClosing() throws Exception {
    try (var listener = subscribe(2L, 5L, false)) {
      transaction(() -> streams.changed(new Change(Kind.PAYMENT, 5L, true, 7L, 8L)), true);
      clock.advance(Duration.ofSeconds(61));
      assertThat(listener.next()).contains("access-token-expired").doesNotContain("invalidate");
    }
    try (var listener = subscribe(2L, 5L, false)) {
      streams.shutdown();
      assertThat(listener.next()).contains("server-shutdown");
    }
  }

  @Test
  void reserve_globalLimit_rejectsAcrossDifferentAccountsAndReclaimsSlots() {
    var limited =
        new ChangeStreamRegistry(
            new ChangeStreamProperties(
                4, 2, 64, 8192, Duration.ofMillis(1), Duration.ofMinutes(15)),
            clock);
    try (var first = limited.reserve(1L, "one", 0, true, mask, clock.instant().plusSeconds(60));
        var second = limited.reserve(2L, "two", 0, true, mask, clock.instant().plusSeconds(60))) {
      first.activate(null);
      second.activate(null);
      assertThatThrownBy(
              () -> limited.reserve(3L, "three", 0, true, mask, clock.instant().plusSeconds(60)))
          .isInstanceOf(ChangeStreamLimitException.class);
    }
    assertThat(limited.connectionCount()).isZero();
  }

  @Test
  void changed_frameByteOverflow_collapsesToAll() throws Exception {
    var limited =
        new ChangeStreamRegistry(
            new ChangeStreamProperties(
                4, 64, 64, 256, Duration.ofMillis(1), Duration.ofMinutes(15)),
            clock);
    try (var listener =
        limited.reserve(1L, "one", 0, true, mask, clock.instant().plusSeconds(60))) {
      listener.activate(null);
      transaction(() -> limited.changed(new Change(Kind.FINANCIAL, 5L, true, 7L, 8L)), true);
      String frame = listener.next();
      assertThat(frame).contains("\"invalidate\":\"ALL\"");
      assertThat(frame.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
    }
  }

  @Test
  void changed_concurrentTransactions_preservesEveryCommittedVersion() throws Exception {
    try (var listener = subscribe(2L, 5L, false);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++) {
        pending.add(
            executor.submit(
                () ->
                    transaction(
                        () -> streams.changed(new Change(Kind.PAYMENT, 5L, true, 7L, 8L)), true)));
      }
      for (var future : pending) {
        future.get(5, TimeUnit.SECONDS);
      }
      assertThat(listener.next()).contains("\"version\":100", "guest.payments");
      assertThat(listener.next()).isEqualTo(": heartbeat\n\n");
    }
  }
}
