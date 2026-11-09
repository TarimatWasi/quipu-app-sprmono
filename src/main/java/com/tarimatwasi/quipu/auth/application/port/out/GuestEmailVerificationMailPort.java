package com.tarimatwasi.quipu.auth.application.port.out;

/** TAR-349: sends the code that proves a guest owns its new email. */
public interface GuestEmailVerificationMailPort {

  /** Queues the email with the code, best-effort; nothing happens without a mail provider. */
  void send(String email, String code, long validMinutes);
}
