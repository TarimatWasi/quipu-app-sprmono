package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A consent the guest accepted (RN-14): inserted once, never updated nor deleted. */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guest_consents")
public class GuestConsentJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "consent_version")
  private String version;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "text_sha256")
  private String textSha256;

  protected GuestConsentJpaEntity() {}

  GuestConsentJpaEntity(GuestConsent consent) {
    this.guestId = consent.guestId();
    this.version = consent.version();
    this.acceptedAt = consent.acceptedAt();
    this.textSha256 = consent.textSha256();
  }

  GuestConsent toDomain() {
    return new GuestConsent(guestId, version, acceptedAt, textSha256);
  }
}
