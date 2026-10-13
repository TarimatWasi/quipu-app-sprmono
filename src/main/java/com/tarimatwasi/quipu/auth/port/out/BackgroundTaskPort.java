package com.tarimatwasi.quipu.auth.port.out;

/** Runs work after the caller has already been answered. */
public interface BackgroundTaskPort {

  /**
   * Runs the task in another thread and returns at once. Nothing the task does reaches the caller:
   * a failure is only logged.
   */
  void run(Runnable task);
}
