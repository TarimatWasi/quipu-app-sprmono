package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded, single-instance transport. Locks protect memory only, never socket I/O (TAR-371/372).
 */
@Component
public class ChangeStreamRegistry implements ChangeSignals {
  private static final long MAX_VERSION = 9007199254740991L;
  private static final int MAX_TRANSACTION_CHANGES = 128;
  private final ChangeStreamProperties properties;
  private final Clock clock;
  private final Set<Subscription> subscriptions = new LinkedHashSet<>();
  private final State state = new State();

  private static final class State {
    private String epoch = UUID.randomUUID().toString();
    private long version;
    private boolean stopping;
  }

  public ChangeStreamRegistry(ChangeStreamProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Reserve before revalidating credentials, so a concurrent revocation also finds this listener.
   */
  public synchronized Subscription reserve(
      Long userId,
      String sidHash,
      long securityVersion,
      boolean administrator,
      MaskSession mask,
      Instant expiresAt) {
    if (state.stopping
        || subscriptions.size() >= properties.total()
        || subscriptions.stream().filter(s -> s.userId.equals(userId)).count()
            >= properties.perAccount()) {
      throw new ChangeStreamLimitException();
    }
    Instant durationEnd = clock.instant().plus(properties.maxDuration());
    var subscription =
        new Subscription(
            userId,
            sidHash,
            securityVersion,
            administrator,
            mask,
            expiresAt.isBefore(durationEnd) ? expiresAt : durationEnd,
            expiresAt.isBefore(durationEnd) ? "access-token-expired" : "max-duration");
    subscriptions.add(subscription);
    return subscription;
  }

  @Override
  public void changed(Change change) {
    pending().add(change);
  }

  @Override
  public void revokeSession(Long userId, String sidHash) {
    pending()
        .close(
            () -> {
              for (var subscription : subscriptions) {
                if (subscription.userId.equals(userId) && subscription.sidHash.equals(sidHash)) {
                  subscription.end("session-revoked");
                }
              }
            });
  }

  @Override
  public void credentialsChanged(Long userId, long newVersion, @Nullable String keptSidHash) {
    pending()
        .close(
            () -> {
              for (var subscription : subscriptions) {
                if (subscription.userId.equals(userId)
                    && subscription.securityVersion < newVersion) {
                  subscription.end(
                      subscription.sidHash.equals(keptSidHash)
                          ? "session-updated"
                          : "session-revoked");
                }
              }
            });
  }

  @Override
  public void accountLocked(Long userId) {
    pending()
        .close(
            () -> {
              for (var subscription : subscriptions) {
                if (subscription.userId.equals(userId)) {
                  subscription.end("session-revoked");
                }
              }
            });
  }

  private Pending pending() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Change signals require an active transaction");
    }
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Pending found && found.owner.equals(this)) {
        return found;
      }
    }
    var pending = new Pending(this);
    TransactionSynchronizationManager.registerSynchronization(pending);
    return pending;
  }

  private synchronized void deliver(Pending pending) {
    // Security wins over invalidations from the same commit.
    pending.closures.forEach(Runnable::run);
    if (!pending.changes.isEmpty()) {
      if (state.version == MAX_VERSION) {
        state.epoch = UUID.randomUUID().toString();
        state.version = 0;
        subscriptions.forEach(s -> s.end("server-shutdown"));
      } else {
        state.version++;
        for (var change : pending.changes) {
          for (var subscription : subscriptions) {
            if (subscription.active
                && subscription.reason == null
                && (subscription.administrator
                    || (change.guestId() != null && change.guestId().equals(subscription.guestId))
                    || change.relatedGuests().contains(subscription.guestId))) {
              subscription.add(change);
            }
          }
        }
      }
    }
    notifyAll();
  }

  /**
   * Existing connections retain their slots until the physical writer exits, even after revocation.
   */
  @PreDestroy
  public synchronized void shutdown() {
    state.stopping = true;
    subscriptions.forEach(s -> s.end("server-shutdown"));
    notifyAll();
  }

  /** Live physical streams, including closing/blocked writers; used to verify resource bounds. */
  public synchronized int connectionCount() {
    return subscriptions.size();
  }

  private static final class Pending implements TransactionSynchronization {
    private final ChangeStreamRegistry owner;
    private final Set<Change> changes = new LinkedHashSet<>();
    private final List<Runnable> closures = new ArrayList<>();

    private Pending(ChangeStreamRegistry owner) {
      this.owner = owner;
    }

    private void bounded() {
      if (changes.size() + closures.size() >= MAX_TRANSACTION_CHANGES) {
        throw new IllegalStateException("Too many changes in one transaction");
      }
    }

    private void add(Change change) {
      bounded();
      changes.add(change);
    }

    private void close(Runnable closure) {
      bounded();
      closures.add(closure);
    }

    @Override
    public void afterCompletion(int status) {
      // Spring isolates failures in this callback; a delivery defect cannot fail a committed
      // business response. STATUS_COMMITTED also excludes rollback/unknown outcomes (TAR-372).
      if (status == STATUS_COMMITTED) {
        owner.deliver(this);
      }
    }
  }

  private record Key(String prefix, @Nullable IdKind kind, @Nullable Long id) {
    private String render(MaskSession mask) {
      return kind == null ? prefix : prefix + ":" + mask.mask(kind, Objects.requireNonNull(id));
    }
  }

  /** One recipient's bounded pending set. Only its request thread writes the returned frames. */
  public final class Subscription implements AutoCloseable {
    private final Long userId;
    private final String sidHash;
    private final long securityVersion;
    private final boolean administrator;
    private final MaskSession mask;
    private final Instant deadline;
    private final String deadlineReason;
    private final Set<Key> keys = new LinkedHashSet<>();
    private @Nullable Long guestId;
    private @Nullable String reason;
    private boolean active;
    private boolean all;
    private boolean finished;
    private long pendingVersion;

    private Subscription(
        Long userId,
        String sidHash,
        long securityVersion,
        boolean administrator,
        MaskSession mask,
        Instant deadline,
        String deadlineReason) {
      this.userId = userId;
      this.sidHash = sidHash;
      this.securityVersion = securityVersion;
      this.administrator = administrator;
      this.mask = mask;
      this.deadline = deadline;
      this.deadlineReason = deadlineReason;
    }

    /** Activate only after the second authorization; revocation during that read cannot be lost. */
    public String activate(@Nullable Long guest) {
      synchronized (ChangeStreamRegistry.this) {
        if (reason != null || !clock.instant().isBefore(deadline)) {
          throw new InvalidBearerTokenException("The stream session is no longer valid");
        }
        guestId = guest;
        active = true;
        return frame(
            "ready", "{\"epoch\":\"" + state.epoch + "\",\"version\":" + state.version + "}");
      }
    }

    private void end(String closingReason) {
      reason = closingReason;
      keys.clear();
      all = false;
    }

    private void key(String name) {
      key(new Key(name, null, null));
    }

    private void key(String name, IdKind kind, @Nullable Long id) {
      if (id != null) {
        key(new Key(name, kind, id));
      }
    }

    private void key(Key key) {
      if (!all) {
        keys.add(key);
        if (keys.size() > properties.pendingKeys()) {
          all = true;
          keys.clear();
        }
      }
    }

    private void add(Change change) {
      pendingVersion = state.version;
      if (expanded(change)) {
        return;
      }
      if (administrator) {
        key("admin.dashboard");
        key("admin.guest", IdKind.GUEST, change.guestId());
        key("admin.environment", IdKind.ENVIRONMENT, change.environmentId());
        if (change.kind() == Kind.GUEST) {
          key("admin.guests");
        } else if (change.kind() == Kind.CONTRACT) {
          key("admin.contracts");
          key("admin.guests");
          key("admin.environments");
          key("admin.credits");
        } else {
          key("admin.payments.queue");
          key("admin.payment", IdKind.PAYMENT, change.paymentId());
          if (change.kind() == Kind.FINANCIAL) {
            key("admin.finance");
            key("admin.credits");
            key("admin.refunds");
          }
        }
      } else if (change.kind() != Kind.GUEST) {
        if (change.contractType()) {
          key("guest.home");
          key("guest.payments");
          if (change.kind() == Kind.CONTRACT) {
            key("guest.contract");
          }
        } else {
          key("guest.stay");
        }
        key("guest.payment", IdKind.PAYMENT, change.paymentId());
      }
    }

    /** TAR-376: resource-specific signals do not invalidate unrelated payment screens. */
    private boolean expanded(Change change) {
      switch (change.kind()) {
        case DIRECTORY -> {
          // Names/codes occur in arbitrary historical payment/contract details. Their ids are
          // intentionally not enumerated: refresh the recipient's authorized visible resources.
          all = true;
          keys.clear();
        }
        case GUEST_RECORD, PROFILE, DOCUMENT -> profileChanged(change);
        case STAY -> stayChanged(change);
        case ENVIRONMENT -> {
          if (administrator) {
            key("admin.environments");
            key("admin.environment", IdKind.ENVIRONMENT, change.environmentId());
          }
        }
        case EXPENSE, FINE -> {
          if (administrator) {
            key("admin.finance");
            if (change.kind() == Kind.FINE) {
              key("admin.guest", IdKind.GUEST, change.guestId());
            }
          }
        }
        case PAYMENT, FINANCIAL, CONTRACT, GUEST -> {
          return false;
        }
      }
      return true;
    }

    private void profileChanged(Change change) {
      if (administrator) {
        key("admin.guest", IdKind.GUEST, change.guestId());
        if (change.kind() != Kind.DOCUMENT) {
          key("admin.guests");
        }
        if (change.kind() == Kind.GUEST_RECORD) {
          key("admin.dashboard");
        }
      } else {
        key("guest.profile");
      }
    }

    private void stayChanged(Change change) {
      if (administrator) {
        key("admin.guest", IdKind.GUEST, change.guestId());
        key("admin.guests");
        key("admin.environments");
        key("admin.environment", IdKind.ENVIRONMENT, change.environmentId());
        key("admin.dashboard");
        key("admin.finance");
      } else {
        key("guest.stay");
      }
    }

    /**
     * Wait for changes/heartbeat/deadline with no database work. No socket runs under this lock.
     */
    public String next() throws InterruptedException {
      synchronized (ChangeStreamRegistry.this) {
        long heartbeatEnd = System.nanoTime() + properties.heartbeat().toNanos();
        while (true) {
          Instant now = clock.instant();
          if (reason == null && !now.isBefore(deadline)) {
            end(deadlineReason);
          }
          if (reason != null) {
            finished = true;
            return frame("closing", "{\"reason\":\"" + reason + "\"}");
          }
          if (all || !keys.isEmpty()) {
            return drain();
          }
          long nanos =
              Math.min(heartbeatEnd - System.nanoTime(), Duration.between(now, deadline).toNanos());
          if (nanos <= 0) {
            return ": heartbeat\n\n";
          }
          TimeUnit.NANOSECONDS.timedWait(ChangeStreamRegistry.this, nanos);
        }
      }
    }

    private String drain() {
      String invalidate =
          all
              ? "\"ALL\""
              : "["
                  + String.join(",", keys.stream().map(k -> "\"" + k.render(mask) + "\"").toList())
                  + "]";
      String changed = changed(invalidate);
      if (changed.getBytes(StandardCharsets.UTF_8).length > properties.frameBytes()) {
        changed = changed("\"ALL\"");
      }
      keys.clear();
      all = false;
      return changed;
    }

    public String closeReason() {
      synchronized (ChangeStreamRegistry.this) {
        return Objects.requireNonNullElse(reason, "complete");
      }
    }

    private String changed(String invalidate) {
      return frame(
          "changed",
          "{\"epoch\":\""
              + state.epoch
              + "\",\"version\":"
              + pendingVersion
              + ",\"invalidate\":"
              + invalidate
              + "}");
    }

    public boolean finished() {
      synchronized (ChangeStreamRegistry.this) {
        return finished;
      }
    }

    @Override
    public void close() {
      synchronized (ChangeStreamRegistry.this) {
        finished = true;
        keys.clear();
        subscriptions.remove(this);
      }
    }
  }

  private static String frame(String event, String json) {
    return "event: " + event + "\ndata: " + json + "\n\n";
  }
}
