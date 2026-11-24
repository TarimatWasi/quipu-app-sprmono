package com.tarimatwasi.quipu.shared.access;

import com.tarimatwasi.quipu.shared.hashing.Sha256;

/** One-way identifier used in the bounded live-connection registry, never logged. */
public final class SessionFingerprint {
  private SessionFingerprint() {}

  public static String of(String sessionId) {
    return Sha256.hex(sessionId);
  }
}
