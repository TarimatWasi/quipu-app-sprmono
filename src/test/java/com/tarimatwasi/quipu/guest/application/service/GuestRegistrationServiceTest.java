package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestAccountEmailTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestAccountEmailTakenException.Field;
import com.tarimatwasi.quipu.guest.application.port.in.GuestInactiveException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestAccessUseCase.RegisterCommand;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.guest.application.port.out.AccountEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountEmailChange;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.EmailChangeOutcome;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** TAR-229: the account email of a guest, at the registration and set by the administrator. */
class GuestRegistrationServiceTest {

  private static final Long ID = 42L;

  private final GuestRepositoryPort guests = mock(GuestRepositoryPort.class);
  private final GuestAccountPort accounts = mock(GuestAccountPort.class);
  private final GuestRegistrationService service =
      new GuestRegistrationService(new GuestAccessIssuer(guests, accounts), accounts);

  private static Guest guest(GuestStatus status) {
    return new Guest(
        ID,
        DocumentType.DNI,
        "12345678",
        null,
        "contacto@example.com",
        null,
        GuestType.CONTRACT,
        status,
        null,
        null,
        null,
        null);
  }

  @Test
  void aRegistrationWhoseContactEmailIsAnAccountEmailIsRefusedBeforeAnyAccess() {
    when(guests.findByDocument(DocumentType.DNI, "12345678")).thenReturn(Optional.empty());
    when(guests.create(any(), anyString(), any(), any(), any()))
        .thenReturn(guest(GuestStatus.PENDING_ACTIVATION));
    doThrow(new AccountEmailAlreadyExistsException(new IllegalStateException("taken")))
        .when(accounts)
        .openNewAccount(ID, "DNI", "12345678", "admin@example.com");

    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterCommand(
                        IdDocumentKind.DNI,
                        "12345678",
                        GuestKind.CONTRACT,
                        null,
                        " admin@example.com ",
                        AccessMode.LINK)))
        .isInstanceOfSatisfying(
            GuestAccountEmailTakenException.class,
            e -> assertThat(e.field()).isEqualTo(Field.CONTACT_EMAIL));
    verify(accounts, never()).openAccount(anyLong(), anyString(), anyString(), any());
    verify(accounts, never()).issueAccess(anyLong(), any());
    verify(accounts, never()).sendAccess(anyString(), any());
  }

  @Test
  void anUnknownGuestIsNotFound() {
    when(guests.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.setAccountEmail(ID, "new@example.com"))
        .isInstanceOf(GuestNotFoundException.class);
    verify(accounts, never()).changeAccountEmail(anyLong(), anyString());
  }

  @Test
  void anInactiveGuestIsRefusedWithoutTouchingTheAccount() {
    when(guests.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.INACTIVE)));

    assertThatThrownBy(() -> service.setAccountEmail(ID, "new@example.com"))
        .isInstanceOfSatisfying(
            GuestInactiveException.class, e -> assertThat(e.guestId()).isEqualTo(ID));
    verify(accounts, never()).changeAccountEmail(anyLong(), anyString());
    verify(accounts, never()).noticeAccountEmailChanged(anyString(), anyString());
  }

  @Test
  void aGuestWithoutAccountIsAnInternalError() {
    when(guests.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    when(accounts.changeAccountEmail(ID, "new@example.com"))
        .thenReturn(new AccountEmailChange(EmailChangeOutcome.NO_ACCOUNT, null));

    assertThatThrownBy(() -> service.setAccountEmail(ID, "new@example.com"))
        .isInstanceOf(IllegalStateException.class);
    verify(accounts, never()).noticeAccountEmailChanged(anyString(), anyString());
  }

  @Test
  void anEmailOfAnotherAccountIsRefusedOnTheEmailField() {
    when(guests.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    when(accounts.changeAccountEmail(ID, "taken@example.com"))
        .thenThrow(new AccountEmailAlreadyExistsException(new IllegalStateException("taken")));

    assertThatThrownBy(() -> service.setAccountEmail(ID, "taken@example.com"))
        .isInstanceOfSatisfying(
            GuestAccountEmailTakenException.class,
            e -> assertThat(e.field()).isEqualTo(Field.EMAIL));
    verify(accounts, never()).noticeAccountEmailChanged(anyString(), anyString());
  }

  @Test
  void aChangeLocksTheGuestFirstAndTellsThePreviousEmail() {
    when(guests.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(accounts.changeAccountEmail(ID, "new@example.com"))
        .thenReturn(new AccountEmailChange(EmailChangeOutcome.CHANGED, "old@example.com"));

    service.setAccountEmail(ID, "  new@example.com ");

    InOrder order = inOrder(guests, accounts);
    order.verify(guests).findByIdForUpdate(ID);
    order.verify(accounts).changeAccountEmail(ID, "new@example.com");
    order.verify(accounts).noticeAccountEmailChanged("old@example.com", "new@example.com");
  }

  @Test
  void theSameEmailOrAFirstEmailTellsNobody() {
    when(guests.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    when(accounts.changeAccountEmail(ID, "same@example.com"))
        .thenReturn(new AccountEmailChange(EmailChangeOutcome.UNCHANGED, null));
    when(accounts.changeAccountEmail(ID, "first@example.com"))
        .thenReturn(new AccountEmailChange(EmailChangeOutcome.CHANGED, null));

    service.setAccountEmail(ID, "same@example.com");
    service.setAccountEmail(ID, "first@example.com");

    verify(accounts).changeAccountEmail(ID, "same@example.com");
    verify(accounts).changeAccountEmail(ID, "first@example.com");
    verify(accounts, never()).noticeAccountEmailChanged(anyString(), anyString());
  }
}
