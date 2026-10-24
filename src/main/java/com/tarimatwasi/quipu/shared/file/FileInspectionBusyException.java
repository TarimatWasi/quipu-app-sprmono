package com.tarimatwasi.quipu.shared.file;

/**
 * The PDF could not be inspected in time because another one was being inspected (TAR-39): it is
 * not a verdict on the file, which may be sent again. Nothing was stored.
 */
public class FileInspectionBusyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The inspection of another file kept the turn too long. */
  public FileInspectionBusyException() {
    super("Another file is being inspected");
  }
}
