package com.tarimatwasi.quipu.bff.adapter.in.rest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** What an id identifies. A masked id only unmasks as the kind it was issued for. */
public enum IdKind {
  ENVIRONMENT("environment"),
  EXPENSE("expense"),
  GUEST("guest");

  private final long tag;

  IdKind(String label) {
    this.tag = tagOf(label);
  }

  /** A fixed 64-bit value that tells this kind from the others inside a masked id. */
  long tag() {
    return tag;
  }

  private static long tagOf(String label) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("quipu-id:" + label).getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
