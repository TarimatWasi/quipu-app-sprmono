package com.tarimatwasi.quipu.shared.masking;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The masking of one session (TAR-227): the key derived for it by {@link IdMasker#session}. It is
 * immutable and keeps no other state, so it may be shared by the threads of a request. It never
 * shows its key, not even in {@link #toString}.
 */
public final class MaskSession {

  private static final int ROUNDS = 4;

  private final SecretKeySpec key;

  MaskSession(SecretKeySpec key) {
    this.key = key;
  }

  /** The UUID that stands for the id in this session. */
  public UUID mask(IdKind kind, long id) {
    if (id < 0) {
      throw new IllegalArgumentException("An id is never negative");
    }
    long left = id;
    long right = kind.tag();
    for (int round = 0; round < ROUNDS; round++) {
      long next = left ^ round(round, right);
      left = right;
      right = next;
    }
    return new UUID(left, right);
  }

  /**
   * The id behind the UUID.
   *
   * @throws UnknownMaskedIdException if the UUID was not issued for this kind in this session
   */
  public long unmask(IdKind kind, UUID masked) {
    long left = masked.getMostSignificantBits();
    long right = masked.getLeastSignificantBits();
    for (int round = ROUNDS - 1; round >= 0; round--) {
      long previous = right ^ round(round, left);
      right = left;
      left = previous;
    }
    if (right != kind.tag() || left < 0) {
      throw new UnknownMaskedIdException(kind);
    }
    return left;
  }

  private long round(int round, long half) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update((byte) round);
      mac.update(ByteBuffer.allocate(Long.BYTES).putLong(half).array());
      return ByteBuffer.wrap(mac.doFinal()).getLong();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  @Override
  public String toString() {
    return "MaskSession[key hidden]";
  }
}
