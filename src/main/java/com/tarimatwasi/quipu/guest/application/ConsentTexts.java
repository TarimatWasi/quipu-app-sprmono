package com.tarimatwasi.quipu.guest.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The consent texts (RF-14, RN-14). The version in force is the property {@code
 * app.consent.version}; its text, and the text of every version that was ever in force, is the
 * classpath resource {@code consent/<version>.txt}, kept so that an accepted text can be shown
 * again. A version without its text fails the startup.
 */
@Component
class ConsentTexts {

  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,50}");

  private final String current;
  private final String currentText;

  ConsentTexts(@Value("${app.consent.version}") String current) {
    this.current = current;
    this.currentText = textOf(current);
  }

  String current() {
    return current;
  }

  String currentText() {
    return currentText;
  }

  /** The text of a version that was in force; its resource is never removed. */
  static String textOf(String version) {
    if (!VERSION.matcher(version).matches()) {
      throw new IllegalStateException("Not a consent version: " + version);
    }
    String path = "consent/" + version + ".txt";
    try (InputStream in = ConsentTexts.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("The consent version " + version + " has no text");
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      if (text.isEmpty()) {
        throw new IllegalStateException("The consent version " + version + " has an empty text");
      }
      return text;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The SHA-256, in hexadecimal, of the text as it is shown (UTF-8). */
  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
