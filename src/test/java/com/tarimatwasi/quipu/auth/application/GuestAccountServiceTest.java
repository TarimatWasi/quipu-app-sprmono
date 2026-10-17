package com.tarimatwasi.quipu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase.GuestAccountState;
import com.tarimatwasi.quipu.support.TestIds;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuestAccountServiceTest {

  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final GuestAccountService service = new GuestAccountService(users);

  private UserAccount account(Long guestId, String document, boolean mustChange, String status) {
    var account =
        new UserAccount(
            TestIds.next(),
            document + "@example.test",
            DocumentType.DNI,
            document,
            "hash",
            Role.GUEST,
            guestId,
            mustChange,
            status,
            0,
            null,
            null);
    users.save(account);
    return account;
  }

  @Test
  void aGuestWhoChoseItsPasswordCanLogInAndHasChosenIt() {
    var guest = TestIds.next();
    account(guest, "11111111", false, "ACTIVE");

    assertThat(service.stateOf(List.of(guest)).get(guest))
        .isEqualTo(new GuestAccountState(true, true));
  }

  @Test
  void aTemporaryPasswordIsNotAChosenOne() {
    var guest = TestIds.next();
    account(guest, "22222222", true, "ACTIVE");

    assertThat(service.stateOf(List.of(guest)).get(guest))
        .isEqualTo(new GuestAccountState(true, false));
  }

  @Test
  void aDisabledAccountCannotLogIn() {
    var guest = TestIds.next();
    account(guest, "33333333", false, "INACTIVE");

    assertThat(service.stateOf(List.of(guest)))
        .containsEntry(guest, new GuestAccountState(false, true));
  }

  @Test
  void aGuestWithoutAccountAndTheAdminsAreNotInTheMap() {
    var withoutAccount = TestIds.next();
    var other = TestIds.next();
    account(other, "44444444", false, "ACTIVE");
    users.save(
        new UserAccount(
            TestIds.next(),
            "admin@example.test",
            DocumentType.DNI,
            "55555555",
            "hash",
            Role.ADMIN,
            null,
            false,
            "ACTIVE",
            0,
            null,
            null));

    var states = service.stateOf(List.of(withoutAccount, other));

    assertThat(states).containsOnlyKeys(other);
    assertThat(service.stateOf(List.of())).isEmpty();
  }

  private void accountOf(Role role, String document, String status) {
    users.save(
        new UserAccount(
            TestIds.next(),
            document + "@example.test",
            DocumentType.DNI,
            document,
            "hash",
            role,
            null,
            false,
            status,
            0,
            null,
            null));
  }

  @Test
  void theDocumentOfAnAdministratorBelongsToAnAdministratorEvenIfInactive() {
    accountOf(Role.ADMIN, "55555555", "ACTIVE");
    accountOf(Role.ADMIN, "66666666", "INACTIVE");

    assertThat(service.documentBelongsToAdministrator("DNI", "55555555")).isTrue();
    assertThat(service.documentBelongsToAdministrator("DNI", "66666666")).isTrue();
  }

  @Test
  void theDocumentOfAGuestOrOfNobodyDoesNotBelongToAnAdministrator() {
    accountOf(Role.GUEST, "77777777", "ACTIVE");

    assertThat(service.documentBelongsToAdministrator("DNI", "77777777")).isFalse();
    assertThat(service.documentBelongsToAdministrator("DNI", "88888888")).isFalse();
    assertThat(service.documentBelongsToAdministrator("CE", "55555555")).isFalse();
  }

  @Test
  void anUnknownDocumentTypeBelongsToNobody() {
    accountOf(Role.ADMIN, "55555555", "ACTIVE");

    assertThat(service.documentBelongsToAdministrator("VISA", "55555555")).isFalse();
  }

  @Test
  void theAccountOfAGuestSaysWhichGuestItIs() {
    var guest = TestIds.next();
    var account = account(guest, "44444444", false, "ACTIVE");

    assertThat(service.guestOf(account.id())).contains(guest);
    assertThat(service.guestOf(TestIds.next())).isEmpty();
  }

  @Test
  void anAccountOfNoGuestHasNoGuest() {
    var admin =
        new UserAccount(
            TestIds.next(),
            "admin@example.test",
            DocumentType.DNI,
            "55555555",
            "hash",
            Role.ADMIN,
            null,
            false,
            "ACTIVE",
            0,
            null,
            null);
    users.save(admin);

    assertThat(service.guestOf(admin.id())).isEmpty();
  }

  @Test
  void disablingAndEnablingTheAccountOfAGuestTouchesOnlyThatAccount() {
    var guest = TestIds.next();
    var other = TestIds.next();
    var account = account(guest, "66666666", false, "ACTIVE");
    var untouched = account(other, "77777777", false, "ACTIVE");

    service.setActive(guest, false);
    assertThat(users.findById(account.id()))
        .get()
        .extracting(UserAccount::status)
        .isEqualTo("INACTIVE");
    assertThat(users.findById(untouched.id()))
        .get()
        .extracting(UserAccount::status)
        .isEqualTo("ACTIVE");

    service.setActive(guest, true);
    assertThat(users.findById(account.id()))
        .get()
        .extracting(UserAccount::status)
        .isEqualTo("ACTIVE");
    service.setActive(TestIds.next(), false); // a guest without account: nothing happens
  }
}
