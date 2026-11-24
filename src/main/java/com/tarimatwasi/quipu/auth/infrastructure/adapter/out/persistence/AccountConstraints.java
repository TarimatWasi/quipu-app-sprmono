package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.ViolatedConstraint;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The unique rules of users that tell what of an account another one holds: its document
 * (uq_users_document, any account) or its email (users_email_key and the case-insensitive
 * uq_users_email_lower). Read by name, never by the message: the language of the server does not
 * matter.
 */
final class AccountConstraints {

  private static final Map<String, Taken> TAKEN =
      Map.of(
          "uq_users_document", Taken.DOCUMENT,
          "users_email_key", Taken.EMAIL,
          "uq_users_email_lower", Taken.EMAIL);

  private AccountConstraints() {}

  /**
   * The failure as the accounts tell it: an {@link AccountTakenException} saying what was taken, or
   * the failure itself when it broke another rule.
   */
  static RuntimeException translated(RuntimeException failure) {
    Taken taken = taken(failure);
    return taken == null ? failure : new AccountTakenException(taken, failure);
  }

  /** What of the account the failed write found taken; null if it broke another rule. */
  static @Nullable Taken taken(Throwable failure) {
    String constraint = ViolatedConstraint.nameOf(failure);
    return constraint == null ? null : TAKEN.get(constraint);
  }
}
