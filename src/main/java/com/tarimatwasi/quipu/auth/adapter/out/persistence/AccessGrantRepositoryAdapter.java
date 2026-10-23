package com.tarimatwasi.quipu.auth.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.port.out.AccessGrantRepositoryPort;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AccessGrantRepositoryAdapter implements AccessGrantRepositoryPort {

  private final AccessGrantJpaRepository jpaRepository;

  AccessGrantRepositoryAdapter(AccessGrantJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public void revokeLive(Long guestId, Instant now) {
    jpaRepository.revokeLive(guestId, now);
  }

  /** Flushes so that a second live access of the guest fails here, not at the commit. */
  @Override
  public void save(Long guestId, AccessMode mode, String tokenHash, Instant expiresAt) {
    jpaRepository.saveAndFlush(new AccessGrantJpaEntity(guestId, mode, tokenHash, expiresAt));
  }

  @Override
  public Optional<Long> findGuestIdByTokenHash(String tokenHash) {
    return jpaRepository.findGuestIdByTokenHash(tokenHash);
  }

  @Override
  public boolean consumeLink(String tokenHash, Instant now) {
    return jpaRepository.consumeLink(tokenHash, now) == 1;
  }

  @Override
  public Optional<LiveTemporaryPassword> findLiveTemporaryPassword(Long guestId) {
    return jpaRepository
        .findByGuestIdAndModeAndUsedAtIsNullAndRevokedAtIsNull(
            guestId, AccessMode.TEMPORARY_PASSWORD)
        .map(
            grant ->
                new LiveTemporaryPassword(
                    Objects.requireNonNull(grant.getId()), grant.tokenHash(), grant.expiresAt()));
  }

  @Override
  public Set<Long> guestsWithLiveTemporaryPassword(Collection<Long> guestIds, Instant now) {
    return guestIds.isEmpty()
        ? Set.of()
        : Set.copyOf(jpaRepository.findGuestsWithLiveTemporaryPassword(guestIds, now));
  }

  @Override
  public boolean isLiveTemporaryPassword(Long grantId, Long guestId, Instant now) {
    return jpaRepository.isLiveTemporaryPassword(grantId, guestId, now);
  }

  @Override
  public boolean isLiveAccess(Long guestId, String tokenHash, Instant now) {
    return jpaRepository.isLiveAccess(guestId, tokenHash, now);
  }

  @Override
  public void consumeTemporaryPassword(Long guestId, Instant now) {
    jpaRepository.consumeTemporaryPassword(guestId, now);
  }

  @Override
  public Optional<Instant> liveExpiry(Long guestId, Instant now) {
    return jpaRepository
        .findByGuestIdAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtAfter(guestId, now)
        .map(AccessGrantJpaEntity::expiresAt);
  }
}
