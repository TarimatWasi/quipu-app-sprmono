package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException.Reason;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.ProfileChange;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.application.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CU-20, CU-23, RN-17: the guest edits its own data; what is absent keeps its value. */
@ExtendWith(MockitoExtension.class)
class OwnProfileServiceTest {

  private static final Long GUEST = 7L;
  private static final Instant DONE = Instant.parse("2026-10-01T15:00:00Z");

  @Mock GuestRepositoryPort guests;
  @Mock GuestAccountPort accounts;

  private OwnProfileService service() {
    return new OwnProfileService(guests, accounts);
  }

  private static Guest guest(GuestType type, GuestStatus status) {
    return new Guest(
        GUEST,
        DocumentType.DNI,
        "12345678",
        "Ana Quispe",
        "ana@example.test",
        "999111222",
        type,
        status,
        null,
        DONE,
        DONE,
        new EmergencyContact("Rosa", "Madre", "988777666"));
  }

  private void locked(Guest guest) {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest));
  }

  private void savedAsIs() {
    when(guests.save(any())).thenAnswer(call -> call.getArgument(0));
    when(accounts.stateOf(any())).thenReturn(Map.of(GUEST, new AccountState(true, true)));
  }

  private Guest saved() {
    ArgumentCaptor<Guest> saved = ArgumentCaptor.captor();
    verify(guests).save(saved.capture());
    return saved.getValue();
  }

  private static ProfileChange name(String name) {
    return new ProfileChange(name, null, null, null);
  }

  @Test
  void onlyThePresentFieldsChangeAndTheRestKeepTheirValue() {
    Guest before = guest(GuestType.CONTRACT, GuestStatus.ACTIVE);
    locked(before);
    savedAsIs();

    GuestSummaryView view =
        service()
            .update(GUEST, GuestKind.CONTRACT, new ProfileChange(null, "977000111", null, null));

    Guest after = saved();
    assertThat(after.phone()).isEqualTo("977000111");
    assertThat(after).usingRecursiveComparison().ignoringFields("phone").isEqualTo(before);
    assertThat(view.name()).isEqualTo("Ana Quispe");
    assertThat(view.hasLoginAccess()).isTrue();
  }

  @Test
  void everyFieldChangesAndTheDocumentTheTypeAndTheStateStay() {
    Guest before = guest(GuestType.TEMPORARY, GuestStatus.ACTIVE);
    locked(before);
    savedAsIs();

    service()
        .update(
            GUEST,
            GuestKind.TEMPORARY,
            new ProfileChange(
                "Ana Maria",
                "977000111",
                "nueva@example.test",
                new EmergencyContactView("Luis", null, "966555444")));

    Guest after = saved();
    assertThat(after.fullName()).isEqualTo("Ana Maria");
    assertThat(after.phone()).isEqualTo("977000111");
    assertThat(after.email()).isEqualTo("nueva@example.test");
    assertThat(after.emergencyContact()).isEqualTo(new EmergencyContact("Luis", null, "966555444"));
    assertThat(after)
        .usingRecursiveComparison()
        .ignoringFields("fullName", "phone", "email", "emergencyContact")
        .isEqualTo(before);
  }

  @Test
  void anEmergencyContactWithoutAnyFieldRemovesIt() {
    locked(guest(GuestType.CONTRACT, GuestStatus.ACTIVE));
    savedAsIs();

    service()
        .update(
            GUEST,
            GuestKind.CONTRACT,
            new ProfileChange(null, null, null, new EmergencyContactView(null, null, null)));

    assertThat(saved().emergencyContact()).isNull();
  }

  @Test
  void anInactiveGuestThatCompletedTheOnboardingMayStillEditIt() {
    locked(guest(GuestType.CONTRACT, GuestStatus.INACTIVE));
    savedAsIs();

    service().update(GUEST, GuestKind.CONTRACT, name("Ana"));

    assertThat(saved().fullName()).isEqualTo("Ana");
  }

  @Test
  void aChangeWithoutFieldsIsRefusedBeforeTheGuestIsRead() {
    assertThatThrownBy(
            () ->
                service()
                    .update(GUEST, GuestKind.CONTRACT, new ProfileChange(null, null, null, null)))
        .isInstanceOf(EmptyGuestUpdateException.class);
    verify(guests, never()).findByIdForUpdate(any());
  }

  @Test
  void theRouteOfTheOtherTypeOfGuestIsRefused() {
    locked(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE));

    assertThatThrownBy(() -> service().update(GUEST, GuestKind.CONTRACT, name("Ana")))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.OTHER_GUEST_TYPE));
    verify(guests, never()).save(any());
  }

  @Test
  void aGuestThatHasNotCompletedTheOnboardingIsRefused() {
    for (GuestStatus status :
        new GuestStatus[] {GuestStatus.ONBOARDING, GuestStatus.PENDING_ACTIVATION}) {
      locked(guest(GuestType.CONTRACT, status));

      assertThatThrownBy(() -> service().update(GUEST, GuestKind.CONTRACT, name("Ana")))
          .as(status.name())
          .isInstanceOfSatisfying(
              OwnProfileRefusedException.class,
              e -> assertThat(e.reason()).isEqualTo(Reason.ONBOARDING_REQUIRED));
    }
    verify(guests, never()).save(any());
  }

  @Test
  void aContactEmailOfAnotherGuestIsTakenEvenWhenTheRaceIsLostAtTheIndex() {
    locked(guest(GuestType.CONTRACT, GuestStatus.ACTIVE));
    when(guests.save(any()))
        .thenThrow(new GuestEmailAlreadyExistsException(new IllegalStateException("unique")));

    assertThatThrownBy(
            () ->
                service()
                    .update(
                        GUEST,
                        GuestKind.CONTRACT,
                        new ProfileChange(null, null, "otra@example.test", null)))
        .isInstanceOf(GuestEmailTakenException.class);
  }
}
