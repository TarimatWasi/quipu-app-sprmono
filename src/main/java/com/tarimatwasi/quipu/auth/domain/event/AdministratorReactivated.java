package com.tarimatwasi.quipu.auth.domain.event;

import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;

/**
 * An administrator gave back the access of another one (TAR-127); its deactivation is stated by
 * {@link AccountsDeactivated}. The actor is who reactivated it.
 */
public record AdministratorReactivated(EventMetadata metadata, Long administratorId)
    implements DomainEvent {

  @Override
  public String subject() {
    return "user/" + administratorId;
  }
}
