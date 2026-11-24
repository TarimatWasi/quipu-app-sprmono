package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import org.jspecify.annotations.Nullable;

/**
 * The password of an account changed: every session issued before {@code securityVersion} ends,
 * except the one that changed it, which goes on (TAR-227).
 *
 * @param keptSessionId the session that goes on; null when none does (a recovery by code)
 */
public record CredentialsChanged(
    EventMetadata metadata, Long userId, long securityVersion, @Nullable Long keptSessionId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + userId;
  }
}
