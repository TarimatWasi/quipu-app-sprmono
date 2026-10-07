package com.tarimatwasi.quipu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.domain.DocumentType;
import com.tarimatwasi.quipu.auth.domain.Role;
import com.tarimatwasi.quipu.auth.domain.UserAccount;
import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase.GuestAccountState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuestAccountServiceTest {

  private final InMemoryUserRepository users = new InMemoryUserRepository();
  private final GuestAccountService service = new GuestAccountService(users);

  private UserAccount account(UUID guestId, String document, boolean mustChange, String status) {
    var account =
        new UserAccount(
            UUID.randomUUID(),
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
    var guest = UUID.randomUUID();
    account(guest, "11111111", false, "ACTIVE");

    assertThat(service.stateOf(List.of(guest)).get(guest))
        .isEqualTo(new GuestAccountState(true, true));
  }

  @Test
  void aTemporaryPasswordIsNotAChosenOne() {
    var guest = UUID.randomUUID();
    account(guest, "22222222", true, "ACTIVE");

    assertThat(service.stateOf(List.of(guest)).get(guest))
        .isEqualTo(new GuestAccountState(true, false));
  }

  @Test
  void aDisabledAccountCannotLogIn() {
    var guest = UUID.randomUUID();
    account(guest, "33333333", false, "INACTIVE");

    assertThat(service.stateOf(List.of(guest)))
        .containsEntry(guest, new GuestAccountState(false, true));
  }

  @Test
  void aGuestWithoutAccountAndTheAdminsAreNotInTheMap() {
    var withoutAccount = UUID.randomUUID();
    var other = UUID.randomUUID();
    account(other, "44444444", false, "ACTIVE");
    users.save(
        new UserAccount(
            UUID.randomUUID(),
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
}
