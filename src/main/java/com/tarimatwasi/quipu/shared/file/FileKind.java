package com.tarimatwasi.quipu.shared.file;

import java.util.Arrays;
import java.util.Optional;

/**
 * The kinds of file that may be uploaded (RF-15, RN-22, RN-37), told by their first bytes: the type
 * the client declares and the extension of its name are never read. A PDF that names JavaScript, a
 * launch action, an action on open, additional actions or an embedded file is none of them (see
 * {@link PdfActiveContent}).
 */
public enum FileKind {
  JPEG("image/jpeg", "jpg"),
  PNG("image/png", "png"),
  PDF("application/pdf", "pdf");

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

  private final String contentType;
  private final String extension;

  FileKind(String contentType, String extension) {
    this.contentType = contentType;
    this.extension = extension;
  }

  /** The kind of the content, or empty if it is none of the three; the size is not checked. */
  public static Optional<FileKind> of(byte[] content) {
    if (startsWith(content, JPEG_MAGIC)) {
      return Optional.of(JPEG);
    }
    if (startsWith(content, PNG_MAGIC)) {
      return Optional.of(PNG);
    }
    if (startsWith(content, PDF_MAGIC)) {
      return PdfActiveContent.found(content) ? Optional.empty() : Optional.of(PDF);
    }
    return Optional.empty();
  }

  /**
   * Inspects the content now when it is a PDF, before the caller opens a transaction or locks a row
   * (TAR-39); {@link #of} on the same array then reuses the verdict instead of parsing it again.
   */
  public static void inspectAhead(byte[] content) {
    if (startsWith(content, PDF_MAGIC)) {
      PdfActiveContent.inspectAhead(content);
    }
  }

  private static boolean startsWith(byte[] content, byte[] magic) {
    return content.length >= magic.length
        && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
  }

  /** The kind with that content type; the tables only let the three through. */
  public static FileKind ofContentType(String contentType) {
    for (FileKind kind : values()) {
      if (kind.contentType.equals(contentType)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Not an uploadable type: " + contentType);
  }

  /** The type that is stored and served, derived from the content and never from the client. */
  public String contentType() {
    return contentType;
  }

  /** The extension of the fixed name the file is downloaded with. */
  public String extension() {
    return extension;
  }
}
