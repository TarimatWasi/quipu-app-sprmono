package com.tarimatwasi.quipu.bff.adapter.in.rest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Inside the service an id is a {@code long}; the BFF shows a UUID. The id and a tag of its kind
 * form one 128-bit block that a keyed Feistel network (four rounds, HMAC-SHA-256 as the round
 * function) turns into a value that looks random. It keeps no state: the same id and key always
 * give the same UUID, it survives a restart, and it can be reversed. Unmasking checks the tag, so a
 * UUID that was not issued for that kind, or was altered, is refused (a chance of 2^-64 to guess
 * one).
 *
 * <p>The result is not a version 4 UUID: every bit carries information. The key is a secret.
 */
@Component
public class IdMasker {

  private static final int ROUNDS = 4;

  private final SecretKeySpec key;

  /** Keeps the key. */
  public IdMasker(IdMaskProperties properties) {
    this.key = new SecretKeySpec(properties.key().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  /** The UUID that stands for the id. */
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
   * @throws UnknownMaskedIdException if the UUID was not issued for this kind
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
}
