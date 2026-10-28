package com.tarimatwasi.quipu.shared.masking;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Inside the service an id is a {@code long}; the BFF shows a UUID that is valid only inside the
 * session it was shown in (TAR-227). Each session has its own key, derived from the configured key
 * and the session id ({@code sid} of the session token): the same id gives a different UUID in
 * every session, and a UUID taken from one session is unknown in any other (the same 404 as a UUID
 * that was never issued). A saved link stops working when its session ends. The configured key
 * never masks an id by itself.
 *
 * <p>The key of a session is HMAC-SHA-256 of the configured key over a fixed, versioned label and
 * the 128 bits of the session id. With it, the id and a tag of its kind form one 128-bit block that
 * a keyed Feistel network (four rounds, HMAC-SHA-256 as the round function) turns into a value that
 * looks random and can be reversed ({@link MaskSession}). Unmasking checks the tag, so a UUID that
 * was not issued for that kind and session, or was altered, is refused (a chance of 2^-64 to guess
 * one).
 *
 * <p>{@link #mask} and {@link #unmask} use the session of the current request ({@link
 * MaskSessionSource}); with none they fail closed ({@link NoMaskSessionException}). The issuer of
 * the session token, which has no session yet, asks for one by its id ({@link #session}).
 *
 * <p>The result is not a version 4 UUID: every bit carries information. The keys are secret.
 */
@Component
public class IdMasker {

  /** Bytes of a session id: 128 random bits. */
  public static final int SESSION_ID_BYTES = 16;

  /** Changing it changes every key: only together with a new version of the derivation. */
  private static final byte[] DERIVATION_LABEL =
      "quipu-idmask-v1".getBytes(StandardCharsets.US_ASCII);

  private static final Base64.Decoder SESSION_ID_DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder SESSION_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();

  private final SecretKeySpec key;
  private final MaskSessionSource sessions;

  /** Keeps the configured key, which only derives the keys of the sessions. */
  public IdMasker(IdMaskProperties properties, MaskSessionSource sessions) {
    this.key = new SecretKeySpec(properties.key().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.sessions = sessions;
  }

  /**
   * The masking of the session with this id: 16 bytes in Base64Url without padding.
   *
   * @throws IllegalArgumentException if it is not a session id
   */
  public MaskSession session(String sessionId) {
    byte[] raw = decode(sessionId);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(DERIVATION_LABEL);
      mac.update((byte) 0);
      return new MaskSession(new SecretKeySpec(mac.doFinal(raw), "HmacSHA256"));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /**
   * The UUID that stands for the id in the session of the current request.
   *
   * @throws NoMaskSessionException if there is no session
   */
  public UUID mask(IdKind kind, long id) {
    return current().mask(kind, id);
  }

  /**
   * The id behind the UUID, in the session of the current request.
   *
   * @throws UnknownMaskedIdException if the UUID was not issued for this kind in this session
   * @throws NoMaskSessionException if there is no session
   */
  public long unmask(IdKind kind, UUID masked) {
    return current().unmask(kind, masked);
  }

  private MaskSession current() {
    return sessions.current().orElseThrow(NoMaskSessionException::new);
  }

  /**
   * Only the canonical spelling is a session id, so one session never has two. The message never
   * repeats the value: it is a credential of the session.
   */
  private static byte[] decode(String sessionId) {
    byte[] raw;
    try {
      raw = SESSION_ID_DECODER.decode(sessionId);
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("Not a session id");
    }
    if (raw.length != SESSION_ID_BYTES
        || !SESSION_ID_ENCODER.encodeToString(raw).equals(sessionId)) {
      throw new IllegalArgumentException("Not a session id");
    }
    return raw;
  }
}
