package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.tarimatwasi.quipu.auth.application.port.in.AccountDocumentTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.AccountEmailTakenException;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccessUseCase;
import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.AccountEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentAlreadyExistsException;
import org.junit.jupiter.api.Test;

/** What auth refuses reaches the guest module in its own words, never as an auth exception. */
class AuthGuestAccountAdapterTest {

  private final GuestAccessUseCase access = mock(GuestAccessUseCase.class);
  private final AuthGuestAccountAdapter adapter =
      new AuthGuestAccountAdapter(mock(GuestAccountUseCase.class), access);

  @Test
  void aDocumentOfAnotherAccountIsTheDocumentOfTheGuestTaken() {
    var taken = new AccountDocumentTakenException(new IllegalStateException("unique"));
    doThrow(taken).when(access).openAccount(5L, "DNI", "12345678", null);
    doThrow(taken).when(access).openNewAccount(5L, "DNI", "12345678", null);
    doThrow(taken).when(access).changeAccountDocument(5L, "DNI", "12345678");

    assertThatThrownBy(() -> adapter.openAccount(5L, "DNI", "12345678", null))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
    assertThatThrownBy(() -> adapter.openNewAccount(5L, "DNI", "12345678", null))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
    assertThatThrownBy(() -> adapter.changeAccountDocument(5L, "DNI", "12345678"))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
  }

  @Test
  void anEmailOfAnotherAccountIsTheEmailTaken() {
    doThrow(new AccountEmailTakenException())
        .when(access)
        .openNewAccount(5L, "DNI", "1", "a@b.test");

    assertThatThrownBy(() -> adapter.openNewAccount(5L, "DNI", "1", "a@b.test"))
        .isInstanceOf(AccountEmailAlreadyExistsException.class);
  }
}
