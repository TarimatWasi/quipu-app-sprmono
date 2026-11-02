package com.tarimatwasi.quipu.environment.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An environment (room or cabin) that a contract or a stay occupies.
 *
 * @param createdAt when it was created: the start of its free time when nothing ever occupied it
 * @param deactivatedAt when it was deactivated; null while it is active (TAR-237)
 * @param version the optimistic-locking version the edit sends back (BE-SPR-DAT-13, TAR-276)
 */
public record Environment(
    Long id,
    String code,
    EnvironmentType type,
    EnvironmentStatus status,
    Instant createdAt,
    @Nullable Instant deactivatedAt,
    long version) {

  /** An environment just as its identity and state, for tests and lookups. */
  public Environment(Long id, String code, EnvironmentType type, EnvironmentStatus status) {
    this(id, code, type, status, Instant.EPOCH, null, 0L);
  }
}
