package com.tarimatwasi.quipu.guest.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The consent texts (RF-14, RN-14). The version in force is the property {@code
 * app.consent.version}; its text, and the text of every version that was ever in force, is the
 * classpath resource {@code consent/<version>.txt}, kept so that an accepted text can be shown
 * again. A version without its text fails the startup.
 *
 * <p>TAR-207: {@code consent/manifest.properties} fixes the SHA-256 of the text of every published
 * version. The startup fails unless every version of the manifest has its text with that hash, the
 * version in force is in the manifest and every {@code consent/*.txt} is in it.
 */
@Component
class ConsentTexts {

  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,50}");
  private static final String MANIFEST = "consent/manifest.properties";

  private final String current;
  private final String currentText;

  ConsentTexts(@Value("${app.consent.version}") String current) {
    verify(current);
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

  /** Checks the published versions against the manifest of the classpath. */
  static void verify(String current) {
    verify(current, manifest(), textVersions());
  }

  /** The messages name versions and hashes, never a text. */
  static void verify(String current, Map<String, String> manifest, Set<String> textVersions) {
    if (!manifest.containsKey(current)) {
      throw new IllegalStateException(
          "The consent version in force " + current + " is not in " + MANIFEST);
    }
    for (String version : new TreeSet<>(textVersions)) {
      if (!manifest.containsKey(version)) {
        throw new IllegalStateException(
            "The consent text " + version + ".txt is not in " + MANIFEST);
      }
    }
    for (Map.Entry<String, String> entry : new TreeMap<>(manifest).entrySet()) {
      String version = entry.getKey();
      if (!textVersions.contains(version)) {
        throw new IllegalStateException("The consent version " + version + " has no text");
      }
      String actual = sha256(textOf(version));
      if (!actual.equalsIgnoreCase(entry.getValue())) {
        throw new IllegalStateException(
            "The text of the published consent version "
                + version
                + " changed: its SHA-256 is "
                + actual
                + ", the manifest fixes "
                + entry.getValue());
      }
    }
  }

  /** The manifest: version to SHA-256 of its text. */
  static Map<String, String> manifest() {
    Properties properties = new Properties();
    try (InputStream in = ConsentTexts.class.getClassLoader().getResourceAsStream(MANIFEST)) {
      if (in == null) {
        throw new IllegalStateException("There is no " + MANIFEST);
      }
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, String> manifest = new HashMap<>();
    properties
        .stringPropertyNames()
        .forEach(v -> manifest.put(v, properties.getProperty(v).strip()));
    return manifest;
  }

  /** The versions that have a text file on the classpath. */
  static Set<String> textVersions() {
    try {
      Set<String> versions = new TreeSet<>();
      for (Resource text :
          new PathMatchingResourcePatternResolver(ConsentTexts.class.getClassLoader())
              .getResources("classpath*:consent/*.txt")) {
        String name = text.getFilename();
        if (name != null) {
          versions.add(name.substring(0, name.length() - ".txt".length()));
        }
      }
      return versions;
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
