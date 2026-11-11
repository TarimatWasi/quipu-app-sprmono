package com.tarimatwasi.quipu.auth.application.port.in;

import org.jspecify.annotations.Nullable;

/** Revalidates an already authenticated stream before its subscription becomes active. */
public interface StreamSessionUseCase {
  /** Only internal identity; no credentials or personal data leave the use case. */
  record Identity(Long userId, @Nullable Long guestId, boolean administrator) {}

  /** Reject a closed, locked, disabled or superseded session before opening the stream. */
  Identity authorize(Long userId, String sidHash, long securityVersion, boolean administrator);
}
