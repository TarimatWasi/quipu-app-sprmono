package com.tarimatwasi.quipu.guest.domain;

import java.time.Duration;
import java.time.Instant;

/** A document the guest uploaded in its onboarding (RN-37); it is never changed nor deleted. */
public record GuestDocument(
    Long id,
    Long guestId,
    String storageKey,
    String originalName,
    String contentType,
    long sizeBytes,
    Instant uploadedAt) {

  /** The documents a guest may keep (RN-37). */
  public static final int MAX_PER_GUEST = 5;

  /** The largest document (RF-15). */
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  /** How long the link the administrator gets opens the file (RN-37). */
  public static final Duration URL_VALIDITY = Duration.ofMinutes(5);
}
