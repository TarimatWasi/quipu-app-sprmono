package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.util.List;

/**
 * Accounts can no longer log in (an administrator, or the account of a deactivated guest, RN-12):
 * every session of theirs ends.
 */
public record AccountsDeactivated(EventMetadata metadata, List<Long> userIds)
    implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + userIds.getFirst();
  }
}
