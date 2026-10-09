package com.tarimatwasi.quipu.auth.domain;

import java.io.Serializable;

/**
 * Who an account is for the person who logs in: the type and the number of an identity document. It
 * is one value that can travel as a single text ({@code DNI:12345678}, what Spring Security calls
 * the username) and be read back as an object, without anyone splitting strings by hand.
 *
 * <p>The number is kept exactly as written: the database tells {@code ab1} from {@code AB1}, and
 * changing that would change who can log in. It cannot contain the separator, so the text always
 * reads back as the same identity.
 */
public record DocumentIdentity(DocumentType type, String number) implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final char SEPARATOR = ':';

  /** Refuses a blank number or one that contains the separator. */
  public DocumentIdentity {
    if (number.isBlank()) {
      throw new IllegalArgumentException("The document number is blank");
    }
    if (number.indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException("The document number cannot contain '" + SEPARATOR + "'");
    }
  }

  /**
   * Reads the single text back as an identity.
   *
   * @throws IllegalArgumentException if the text is not {@code TYPE:number}
   */
  public static DocumentIdentity parse(String text) {
    int at = text.indexOf(SEPARATOR);
    if (at < 0) {
      throw new IllegalArgumentException("Not a document identity: no '" + SEPARATOR + "'");
    }
    return new DocumentIdentity(
        DocumentType.valueOf(text.substring(0, at)), text.substring(at + 1));
  }

  /** The single text: {@code TYPE:number}. */
  @Override
  public String toString() {
    return type.name() + SEPARATOR + number;
  }
}
