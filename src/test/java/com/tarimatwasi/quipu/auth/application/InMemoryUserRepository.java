package com.tarimatwasi.quipu.auth.application;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.out.UserRepositoryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The user store of the application tests: same behavior as the port, without a database. */
final class InMemoryUserRepository implements UserRepositoryPort {

  private record ResetToken(String hash, Instant expiresAt) {}

  private final Map<String, UserAccount> byDocument = new HashMap<>();
  private final Map<UUID, UserAccount> byId = new HashMap<>();
  private final Map<UUID, ResetToken> resetTokens = new HashMap<>();

  void save(UserAccount user) {
    byDocument.put(user.documentType() + ":" + user.documentNumber(), user);
    byId.put(user.id(), user);
  }

  UserAccount find(UUID id) {
    return Objects.requireNonNull(byId.get(id));
  }

  /** The stored hash of the account's reset code, if it has one. */
  Optional<String> storedResetHash(UUID id) {
    return Optional.ofNullable(resetTokens.get(id)).map(ResetToken::hash);
  }

  @Override
  public Optional<UserAccount> findByDocument(DocumentType documentType, String documentNumber) {
    return Optional.ofNullable(byDocument.get(documentType + ":" + documentNumber));
  }

  @Override
  public Optional<UserAccount> findById(UUID id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public void changePassword(UUID id, String newPasswordHash) {
    UserAccount user = find(id);
    save(withPassword(user, newPasswordHash));
    resetTokens.remove(id);
  }

  @Override
  public Optional<UserAccount> findByEmail(String email) {
    return byId.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
  }

  @Override
  public Optional<Instant> findResetTokenExpiry(UUID id) {
    return Optional.ofNullable(resetTokens.get(id)).map(ResetToken::expiresAt);
  }

  @Override
  public void saveResetToken(UUID id, String tokenHash, Instant expiresAt) {
    resetTokens.put(id, new ResetToken(tokenHash, expiresAt));
  }

  @Override
  public Optional<PendingReset> findPendingReset(String tokenHash) {
    return resetTokens.entrySet().stream()
        .filter(e -> e.getValue().hash().equals(tokenHash))
        .findFirst()
        .map(e -> new PendingReset(find(e.getKey()), e.getValue().expiresAt()));
  }

  @Override
  public void resetPassword(UUID id, String newPasswordHash) {
    changePassword(id, newPasswordHash);
    resetTokens.remove(id);
  }

  @Override
  public void registerFailedLogin(UUID id, Instant now, int maxAttempts, Duration lockDuration) {
    UserAccount user = find(id);
    int attempts = user.failedLoginAttempts();
    Instant lockedUntil = user.lockedUntil();
    if (lockedUntil != null && !lockedUntil.isAfter(now)) {
      attempts = 0;
      lockedUntil = null;
    }
    attempts++;
    if (attempts >= maxAttempts) {
      lockedUntil = now.plus(lockDuration);
    }
    save(withLock(user, attempts, lockedUntil));
  }

  @Override
  public void clearFailedLogins(UUID id) {
    save(withLock(find(id), 0, null));
  }

  private static UserAccount withLock(
      UserAccount user, int attempts, @Nullable Instant lockedUntil) {
    return new UserAccount(
        user.id(),
        user.email(),
        user.documentType(),
        user.documentNumber(),
        user.passwordHash(),
        user.role(),
        user.guestId(),
        user.mustChangePassword(),
        user.status(),
        attempts,
        lockedUntil);
  }

  private static UserAccount withPassword(UserAccount user, String passwordHash) {
    return new UserAccount(
        user.id(),
        user.email(),
        user.documentType(),
        user.documentNumber(),
        passwordHash,
        user.role(),
        user.guestId(),
        false,
        user.status(),
        0,
        null);
  }
}
