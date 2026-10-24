package com.tarimatwasi.quipu.shared.file;

/**
 * The PDF could not be inspected in time (TAR-39): another one was being inspected, or its own
 * inspection ran past its deadline. It is not a verdict on the file, which may be sent again.
 * Nothing was stored.
 */
public class FileInspectionBusyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The inspection did not end in time. */
  public FileInspectionBusyException() {
    super("The file could not be inspected in time");
  }
}
