package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.in.WeakPasswordException;
import com.tarimatwasi.quipu.auth.application.port.out.AccessGrantRepositoryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** SEG-03 and the bcrypt limit: at least 8 characters, at most 72 bytes in UTF-8. */
final class PasswordPolicy {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_BYTES = 72;

  private PasswordPolicy() {}

  /**
   * TAR-36: the single-use token or code presented to set the password cannot become the password,
   * or whoever kept the link could sign in for good. Compared in constant time.
   */
  static void requireNotTheCredential(String password, String singleUseCredential) {
    if (MessageDigest.isEqual(
        password.getBytes(StandardCharsets.UTF_8),
        singleUseCredential.getBytes(StandardCharsets.UTF_8))) {
      throw new WeakPasswordException(WeakPasswordException.Reason.SINGLE_USE_CREDENTIAL);
    }
  }

  /**
   * TAR-36: every route that sets a password for good goes through here. A live link or temporary
   * password of the guest cannot become its password: once the access is revoked, whoever kept the
   * shared secret would still sign in. Does nothing for an account of no guest.
   */
  static void requireNotALiveAccess(
      AccessGrantRepositoryPort grants, @Nullable Long guestId, String password, Instant now) {
    if (guestId != null && grants.isLiveAccess(guestId, ResetCodeIssuer.hash(password), now)) {
      throw new WeakPasswordException(WeakPasswordException.Reason.SINGLE_USE_CREDENTIAL);
    }
  }

  static void require(String password) {
    if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
      throw new WeakPasswordException(WeakPasswordException.Reason.TOO_SHORT);
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new WeakPasswordException(WeakPasswordException.Reason.TOO_LONG);
    }
  }
}
