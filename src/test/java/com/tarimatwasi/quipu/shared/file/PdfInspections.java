package com.tarimatwasi.quipu.shared.file;

/** For the tests of the upload routes: keeps the inspection of PDFs busy while an action runs. */
public final class PdfInspections {

  /** An action of a test, which may throw. */
  @FunctionalInterface
  public interface Action {
    /** Runs the action. */
    void run() throws Exception;
  }

  private PdfInspections() {}

  /** Runs the action while another file holds the inspection, as if it were taking long. */
  public static void whileBusy(Action action) throws Exception {
    PdfActiveContent.INSPECTION.acquire();
    try {
      action.run();
    } finally {
      PdfActiveContent.INSPECTION.release();
    }
  }
}
