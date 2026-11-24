package com.tarimatwasi.quipu.shared.hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of UTF-8 text; every Java platform must provide the algorithm. */
public final class Sha256 {
  private Sha256() {}

  /** The raw digest of the text encoded as UTF-8. */
  public static byte[] digest(String text) {
    return digest(text.getBytes(StandardCharsets.UTF_8));
  }

  /** The raw digest of the bytes. */
  public static byte[] digest(byte[] content) {
    return digest(content, "SHA-256");
  }

  /** Every Java platform provides SHA-256 (MessageDigest); another algorithm may be missing. */
  static byte[] digest(byte[] content, String algorithm) {
    try {
      return MessageDigest.getInstance(algorithm).digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("No " + algorithm + " digest on this platform", e);
    }
  }

  /** The digest of the text (UTF-8) in lowercase hexadecimal: 64 characters. */
  public static String hex(String text) {
    return HexFormat.of().formatHex(digest(text));
  }

  /** The digest of the bytes in lowercase hexadecimal: 64 characters. */
  public static String hex(byte[] content) {
    return HexFormat.of().formatHex(digest(content));
  }
}
