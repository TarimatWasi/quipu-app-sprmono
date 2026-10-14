package com.tarimatwasi.quipu.shared.file;

/** The name of an uploaded file, made safe to store and to show. */
public final class FileNames {

  /** VARCHAR(255) of the columns that keep the original name. */
  private static final int MAX_LENGTH = 255;

  private FileNames() {}

  /**
   * The name is only a label for the download: no directories (neither / nor \), no control or
   * format characters (such as the bidi override U+202E, which can disguise an extension), at most
   * 255 characters, and never empty: a name with nothing left, or only dots, becomes the fallback.
   */
  public static String sanitized(String name, String fallback) {
    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    String clean = base.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").strip();
    if (clean.length() > MAX_LENGTH) {
      clean = clean.substring(0, MAX_LENGTH);
    }
    return clean.isEmpty() || clean.chars().allMatch(c -> c == '.') ? fallback : clean;
  }
}
