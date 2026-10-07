package com.tarimatwasi.quipu.auth.port.out;

/** Counts login attempts per account, so guessing one account is capped (SEC-01, TAR-124). */
public interface LoginAttemptLimiterPort {

  /**
   * Counts one attempt on the account.
   *
   * @return 0 if the attempt is allowed; otherwise the whole seconds to wait before the next one
   *     (at least 1)
   */
  long tryAcquire(String documentType, String documentNumber);
}
