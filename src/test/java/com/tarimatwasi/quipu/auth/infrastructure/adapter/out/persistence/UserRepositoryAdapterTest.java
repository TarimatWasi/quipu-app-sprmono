package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.out.AccountAlreadyExistsException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException;
import com.tarimatwasi.quipu.auth.application.port.out.AccountTakenException.Taken;
import com.tarimatwasi.quipu.auth.application.port.out.SessionTokenPort;
import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.AccountJwtAuthenticationConverter;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TAR-38: deactivating or reactivating a guest writes only the status of its account, so a password
 * change or a failed login committed by another transaction meanwhile is not overwritten.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class UserRepositoryAdapterTest {

  @Autowired UserRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired SessionTokenPort tokens;
  @Autowired JwtDecoder decoder;
  @Autowired AccountJwtAuthenticationConverter sessions;
  @Autowired AdministratorStoreAdapter administrators;

  private final Long guestId = TestIds.next();
  private final Long accountId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '12345678', 'CONTRACT', 'ACTIVE')",
        guestId);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status, failed_login_attempts) VALUES (?,"
            + " 'a@example.test', 'DNI', '12345678', 'old-hash', 'GUEST', ?, FALSE, 'ACTIVE', 0)",
        accountId,
        guestId);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  /** TAR-349: whether the email is of another account, ignoring case; its own is not taken. */
  @Test
  void emailBelongsToAnotherAccount_ignoresCaseAndTheAccountItself() {
    assertThat(adapter.emailBelongsToAnotherAccount("A@Example.test", accountId)).isFalse();
    assertThat(adapter.emailBelongsToAnotherAccount("A@Example.test", TestIds.next())).isTrue();
    assertThat(adapter.emailBelongsToAnotherAccount("libre@example.test", accountId)).isFalse();
  }

  @Test
  void reactivatingAGuestNeverRevivesItsOldSession() {
    String old = tokens.issue(accountId.toString());
    assertThat(sessions.convert(decoder.decode(old))).isNotNull();
    transaction.executeWithoutResult(tx -> adapter.setGuestAccountStatus(guestId, "INACTIVE"));
    transaction.executeWithoutResult(tx -> adapter.setGuestAccountStatus(guestId, "INACTIVE"));
    transaction.executeWithoutResult(tx -> adapter.setGuestAccountStatus(guestId, "ACTIVE"));

    assertThat(adapter.securityVersion(accountId)).isEqualTo(1L);
    assertThatThrownBy(() -> sessions.convert(decoder.decode(old)))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThat(sessions.convert(decoder.decode(tokens.issue(accountId.toString())))).isNotNull();
  }

  @Test
  void reactivatingAnAdministratorNeverRevivesItsOldSession() {
    jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", accountId);
    String old = tokens.issue(accountId.toString());
    transaction.executeWithoutResult(
        tx -> administrators.setAdministratorStatus(accountId, "INACTIVE", accountId));
    transaction.executeWithoutResult(
        tx -> administrators.setAdministratorStatus(accountId, "ACTIVE", accountId));

    assertThat(adapter.securityVersion(accountId)).isEqualTo(1L);
    assertThatThrownBy(() -> sessions.convert(decoder.decode(old)))
        .isInstanceOf(InvalidBearerTokenException.class);
  }

  @Test
  void aReplacementTokenUsesTheVersionOfThePasswordChangeInItsTransaction() {
    String old = tokens.issue(accountId.toString());
    String replacement =
        transaction.execute(
            tx -> {
              assertThat(adapter.findByIdForUpdate(accountId)).isPresent();
              adapter.changePassword(accountId, "new-hash", Instant.EPOCH);
              return tokens.renew(
                  accountId.toString(),
                  Objects.requireNonNull(decoder.decode(old).getClaimAsString("sid")));
            });

    assertThat(adapter.securityVersion(accountId)).isEqualTo(1L);
    assertThatThrownBy(() -> sessions.convert(decoder.decode(old)))
        .isInstanceOf(InvalidBearerTokenException.class);
    assertThat(sessions.convert(decoder.decode(replacement))).isNotNull();
    assertThat(decoder.decode(replacement).<Long>getClaim("sv")).isEqualTo(1L);
    assertThat(decoder.decode(replacement).getClaimAsString("sid"))
        .isEqualTo(Objects.requireNonNull(decoder.decode(old).getClaimAsString("sid")));
  }

  @Test
  void rollingBackAPasswordChangeAlsoRollsBackItsRevocation() {
    String old = tokens.issue(accountId.toString());
    transaction.executeWithoutResult(
        tx -> {
          assertThat(adapter.findByIdForUpdate(accountId)).isPresent();
          adapter.changePassword(accountId, "new-hash", Instant.EPOCH);
          assertThat(adapter.securityVersion(accountId)).isEqualTo(1L);
          tx.setRollbackOnly();
        });

    assertThat(adapter.securityVersion(accountId)).isZero();
    assertThat(sessions.convert(decoder.decode(old))).isNotNull();
  }

  @Test
  void theStatusChangeKeepsWhatAnotherTransactionWroteMeanwhile() {
    changeStatusAfterAnotherTransaction("INACTIVE", "hash-1", 1);
    assertThat(column("status", String.class)).isEqualTo("INACTIVE");

    changeStatusAfterAnotherTransaction("ACTIVE", "hash-2", 2);
    assertThat(column("status", String.class)).isEqualTo("ACTIVE");
  }

  /**
   * The account is first read into the persistence context; another thread then commits a new
   * password and a failed login; only then the status changes, in the same transaction as the read.
   */
  private void changeStatusAfterAnotherTransaction(String status, String hash, int attempts) {
    transaction.executeWithoutResult(
        tx -> {
          assertThat(adapter.findAllByGuestIds(List.of(guestId))).hasSize(1);
          CompletableFuture.runAsync(
                  () ->
                      jdbc.update(
                          "UPDATE users SET password_hash = ?, failed_login_attempts = ?"
                              + " WHERE id = ?",
                          hash,
                          attempts,
                          accountId))
              .join();
          adapter.setGuestAccountStatus(guestId, status);
        });
    assertThat(column("password_hash", String.class)).isEqualTo(hash);
    assertThat(column("failed_login_attempts", Integer.class)).isEqualTo(attempts);
  }

  private <T> @Nullable T column(String name, Class<T> type) {
    return jdbc.queryForObject("SELECT " + name + " FROM users WHERE id = ?", type, accountId);
  }

  // ------------------------------------------------------------------ TAR-36

  private Long newGuest(String number) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', 'PENDING_ACTIVATION')",
        id,
        number);
    return id;
  }

  /** An email taken meanwhile leaves the account without email, in a usable transaction. */
  @Test
  void aGuestAccountWhoseEmailWasTakenMeanwhileIsCreatedWithoutEmail() {
    Long guest = newGuest("23456789");

    transaction.executeWithoutResult(
        tx ->
            adapter.insertGuestAccount(
                guest, DocumentType.DNI, "23456789", "A@example.test", "unusable"));

    assertThat(
            jdbc.queryForObject(
                "SELECT coalesce(email, 'none') FROM users WHERE guest_id = ?",
                String.class,
                guest))
        .isEqualTo("none");
  }

  @Test
  void aGuestAccountForADocumentWithAnAccountIsRefused() {
    Long guest = newGuest("34567890");

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    tx ->
                        adapter.insertGuestAccount(
                            guest, DocumentType.DNI, "12345678", null, "unusable")))
        .isInstanceOf(AccountAlreadyExistsException.class);
  }

  @Test
  void openingTheAccountOfAGuestThatHasOneChangesNothing() {
    transaction.executeWithoutResult(
        tx -> adapter.insertGuestAccount(guestId, DocumentType.DNI, "99999999", null, "x"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
  }

  /** The document a guest corrects to is the one of another account: it is refused. */
  @Test
  void aDocumentOfAnotherAccountCannotBeTheNewOne() {
    Long other = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '87654321', 'CONTRACT', 'ACTIVE')",
        other);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status, failed_login_attempts) VALUES (?,"
            + " 'b@example.test', 'DNI', '87654321', 'h', 'GUEST', ?, FALSE, 'ACTIVE', 0)",
        TestIds.next(),
        other);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> adapter.changeGuestDocument(other, DocumentType.DNI, "12345678")))
        .isInstanceOf(AccountAlreadyExistsException.class);
  }

  // ------------------------------------------------------------------ races, made to happen

  private final UserJpaRepository racedRepository = mock(UserJpaRepository.class);

  /** Each insert answers what a simultaneous request left: 1 if it wrote the row, 0 if not. */
  private UserRepositoryAdapter racing(int... inserted) {
    UserRepositoryAdapter racing =
        spy(new UserRepositoryAdapter(racedRepository, mock(JdbcClient.class)));
    var stub = doReturn(inserted[0]);
    for (int i = 1; i < inserted.length; i++) {
      stub = stub.doReturn(inserted[i]);
    }
    stub.when(racing).insertGuest(anyLong(), any(), anyString(), any(), anyString());
    return racing;
  }

  private void theAccountOfTheGuestIs(boolean... committed) {
    var answer = when(racedRepository.findByGuestIdIn(List.of(guestId)));
    for (boolean there : committed) {
      answer = answer.thenReturn(there ? List.of(new UserJpaEntity()) : List.of());
    }
  }

  private void theDocumentIsTaken(boolean taken) {
    when(racedRepository.findByDocumentTypeAndDocumentNumber(DocumentType.DNI, "12345678"))
        .thenReturn(taken ? Optional.of(new UserJpaEntity()) : Optional.empty());
  }

  @Test
  void anAccountTheGuestGotMeanwhile_isNotInsertedAgain() {
    theAccountOfTheGuestIs(true);

    racing(0).insertGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h");
    racing(0).insertNewGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h");
  }

  /** Only the email was taken, and then the account was committed by the other request. */
  @Test
  void anAccountCommittedBetweenTheTwoInserts_isLeftAsItIs() {
    theAccountOfTheGuestIs(false, true);
    theDocumentIsTaken(false);

    racing(0, 0).insertGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h");
  }

  @Test
  void aDocumentOfAnotherAccount_isRefused() {
    theAccountOfTheGuestIs(false, false);
    theDocumentIsTaken(false);

    assertThatThrownBy(
            () ->
                racing(0, 0)
                    .insertGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h"))
        .isInstanceOf(AccountAlreadyExistsException.class);
  }

  /** What another account holds: its document, or its email when the document is free. */
  @Test
  void aNewAccountTellsWhatAnotherAccountHolds() {
    theAccountOfTheGuestIs(false);
    theDocumentIsTaken(true);
    assertThatThrownBy(
            () ->
                racing(0)
                    .insertNewGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h"))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.DOCUMENT));

    theDocumentIsTaken(false);
    assertThatThrownBy(
            () -> racing(0).insertNewGuestAccount(guestId, DocumentType.DNI, "12345678", null, "h"))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.DOCUMENT));
    assertThatThrownBy(
            () ->
                racing(0)
                    .insertNewGuestAccount(guestId, DocumentType.DNI, "12345678", "a@b.test", "h"))
        .isInstanceOfSatisfying(
            AccountTakenException.class, e -> assertThat(e.taken()).isEqualTo(Taken.EMAIL));
  }
}
