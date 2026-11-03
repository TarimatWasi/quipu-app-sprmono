package com.tarimatwasi.quipu.guest.application.port.in;

import java.time.LocalDate;

/**
 * The dates of a stay cross another stay of the same guest. It carries the dates of the stay that
 * is crossed (the one that starts first, if several), so the text can name it (TAR-247).
 */
public class StayOverlapException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final LocalDate otherStart;
  private final LocalDate otherEnd;

  /**
   * Creates the exception.
   *
   * @param otherStart the first day of the stay that is crossed
   * @param otherEnd the last day of the stay that is crossed
   */
  public StayOverlapException(LocalDate otherStart, LocalDate otherEnd) {
    super("The dates cross another stay of the guest");
    this.otherStart = otherStart;
    this.otherEnd = otherEnd;
  }

  /** The first day of the stay that is crossed. */
  public LocalDate otherStart() {
    return otherStart;
  }

  /** The last day of the stay that is crossed. */
  public LocalDate otherEnd() {
    return otherEnd;
  }
}
