package com.tarimatwasi.quipu.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentLockedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestDocumentException;
import com.tarimatwasi.quipu.guest.port.in.InvalidGuestStayException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.OnboardingProgress;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.StatusFilter;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestServiceTest {

  private static final Long ID = TestIds.next();
  private static final Instant NOW = Instant.parse("2026-10-06T15:00:00Z");

  @Mock GuestRepositoryPort repository;
  @Mock GuestAccountPort accounts;

  private GuestService service() {
    return new GuestService(repository, accounts);
  }

  private static Guest guest(GuestStatus status) {
    return guest(status, GuestType.CONTRACT, "12345678");
  }

  private static Guest guest(GuestStatus status, GuestType type, String number) {
    return new Guest(
        ID,
        DocumentType.DNI,
        number,
        null,
        null,
        null,
        type,
        status,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static Guest with(
      Guest base, @Nullable Instant personalData, @Nullable Instant documents) {
    return new Guest(
        base.id(),
        base.documentType(),
        base.documentNumber(),
        "Ana Quispe",
        base.email(),
        "999111222",
        base.type(),
        base.status(),
        base.statusBeforeInactive(),
        personalData,
        documents,
        base.stayStartDate(),
        base.stayEndDate(),
        base.agreedAmount(),
        base.emergencyContact());
  }

  private static Guest inactiveFrom(GuestStatus before, Guest base) {
    return new Guest(
        base.id(),
        base.documentType(),
        base.documentNumber(),
        base.fullName(),
        base.email(),
        base.phone(),
        base.type(),
        GuestStatus.INACTIVE,
        before,
        base.personalDataCompletedAt(),
        base.documentsStepCompletedAt(),
        null,
        null,
        null,
        null);
  }

  private void accountIs(boolean canLogIn, boolean passwordChosen) {
    when(accounts.stateOf(Set.of(ID)))
        .thenReturn(Map.of(ID, new AccountState(canLogIn, passwordChosen)));
  }

  private void noAccount() {
    when(accounts.stateOf(Set.of(ID))).thenReturn(Map.of());
  }

  // --- list ---

  @Test
  void currentHidesOnlyTheInactiveGuests() {
    when(repository.findAll(
            List.of(GuestStatus.PENDING_ACTIVATION, GuestStatus.ONBOARDING, GuestStatus.ACTIVE),
            null))
        .thenReturn(List.of(guest(GuestStatus.PENDING_ACTIVATION)));
    noAccount();

    var list = service().list(StatusFilter.CURRENT, null);

    assertThat(list).hasSize(1);
    assertThat(list.get(0).status()).isEqualTo(GuestState.PENDING_ACTIVATION);
    assertThat(list.get(0).hasLoginAccess()).isFalse();
    assertThat(list.get(0).name()).isNull();
  }

  @Test
  void allDoesNotFilterByStatusAndTheTypeIsPassedOn() {
    when(repository.findAll(null, GuestType.TEMPORARY)).thenReturn(List.of());

    assertThat(service().list(StatusFilter.ALL, GuestKind.TEMPORARY)).isEmpty();
  }

  @Test
  void aSingleStatusFilterAsksForThatStatus() {
    when(repository.findAll(List.of(GuestStatus.INACTIVE), null)).thenReturn(List.of());

    assertThat(service().list(StatusFilter.INACTIVE, null)).isEmpty();
  }

  @Test
  void aGuestWhoseAccountCanLogInHasLoginAccess() {
    when(repository.findAll(null, null)).thenReturn(List.of(guest(GuestStatus.ACTIVE)));
    accountIs(true, true);

    assertThat(service().list(StatusFilter.ALL, null).get(0).hasLoginAccess()).isTrue();
  }

  // --- get / onboarding progress ---

  @Test
  void aGuestPendingActivationHasNotStarted() {
    when(repository.findById(ID)).thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    noAccount();

    var detail = service().get(ID);

    assertThat(detail.onboardingProgress()).isEqualTo(OnboardingProgress.NOT_STARTED);
    assertThat(detail.documentCount()).isZero();
    assertThat(detail.accessExpiresAt()).isNull();
    assertThat(detail.summary().documentType()).isEqualTo(IdDocumentKind.DNI);
    assertThat(detail.summary().type()).isEqualTo(GuestKind.CONTRACT);
  }

  @Test
  void onboardingProgressFollowsThePasswordThePersonalDataAndTheDocuments() {
    var onboarding = guest(GuestStatus.ONBOARDING);
    when(repository.findById(ID))
        .thenReturn(Optional.of(onboarding))
        .thenReturn(Optional.of(onboarding))
        .thenReturn(Optional.of(with(onboarding, NOW, null)))
        .thenReturn(Optional.of(with(onboarding, NOW, NOW)));
    when(accounts.stateOf(Set.of(ID)))
        .thenReturn(Map.of(ID, new AccountState(true, false)))
        .thenReturn(Map.of(ID, new AccountState(true, true)));

    var service = service();
    assertThat(service.get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.NOT_STARTED);
    assertThat(service.get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.PASSWORD_SET);
    assertThat(service.get(ID).onboardingProgress())
        .isEqualTo(OnboardingProgress.PERSONAL_DATA_SAVED);
    assertThat(service.get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.DOCUMENTS_DONE);
  }

  @Test
  void anActiveGuestIsCompleted() {
    when(repository.findById(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    accountIs(true, true);

    assertThat(service().get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.COMPLETED);
  }

  @Test
  void anInactiveGuestKeepsTheProgressItHadWhenItWasDeactivated() {
    var active = inactiveFrom(GuestStatus.ACTIVE, guest(GuestStatus.ACTIVE));
    var pending = inactiveFrom(GuestStatus.PENDING_ACTIVATION, guest(GuestStatus.ACTIVE));
    var onboarding =
        inactiveFrom(GuestStatus.ONBOARDING, with(guest(GuestStatus.ACTIVE), NOW, null));
    when(repository.findById(ID))
        .thenReturn(Optional.of(active))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(onboarding));
    when(accounts.stateOf(Set.of(ID))).thenReturn(Map.of(ID, new AccountState(false, true)));

    var service = service();
    assertThat(service.get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.COMPLETED);
    assertThat(service.get(ID).onboardingProgress()).isEqualTo(OnboardingProgress.NOT_STARTED);
    assertThat(service.get(ID).onboardingProgress())
        .isEqualTo(OnboardingProgress.PERSONAL_DATA_SAVED);
  }

  @Test
  void anUnknownGuestIsNotFound() {
    when(repository.findById(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().get(ID)).isInstanceOf(GuestNotFoundException.class);
  }

  // --- update ---

  @Test
  void anUpdateWithNoFieldIsRejected() {
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, null, null, null, null)))
        .isInstanceOf(EmptyGuestUpdateException.class);
    verify(repository, never()).findByIdForUpdate(any());
  }

  @Test
  void updatingAnUnknownGuestIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().update(ID, new UpdateCommand(null, "87654321", null, null, null)))
        .isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void theDocumentIsCorrectedWhileThePendingGuestHasNotActivated() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    noAccount();

    var summary = service().update(ID, new UpdateCommand(null, " 87654321 ", null, null, null));

    assertThat(summary.documentNumber()).isEqualTo("87654321");
    var saved = ArgumentCaptor.forClass(Guest.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().documentNumber()).isEqualTo("87654321");
    assertThat(saved.getValue().status()).isEqualTo(GuestStatus.PENDING_ACTIVATION);
  }

  @Test
  void aCePassportNumberIsStoredInUpperCase() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    noAccount();

    var summary =
        service()
            .update(ID, new UpdateCommand(IdDocumentKind.PASSPORT, "ab123456", null, null, null));

    assertThat(summary.documentType()).isEqualTo(IdDocumentKind.PASSPORT);
    assertThat(summary.documentNumber()).isEqualTo("AB123456");
  }

  @Test
  void theDocumentCannotChangeOnceTheGuestStartedTheOnboarding() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ONBOARDING)));

    assertThatThrownBy(
            () -> service().update(ID, new UpdateCommand(null, "87654321", null, null, null)))
        .isInstanceOf(GuestDocumentLockedException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void sendingTheSameDocumentAgainIsNotAChangeEvenForAnActiveGuest() {
    var active = guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678");
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(active));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    accountIs(true, true);

    var summary =
        service()
            .update(
                ID,
                new UpdateCommand(
                    IdDocumentKind.DNI,
                    "12345678",
                    LocalDate.parse("2026-10-10"),
                    null,
                    new BigDecimal("150.50")));

    assertThat(summary.documentNumber()).isEqualTo("12345678");
    assertThat(summary.hasLoginAccess()).isTrue();
  }

  @Test
  void aDniNeedsEightDigits() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));

    for (var bad : List.of("1234567", "123456789", "1234567A", "", "   ")) {
      assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, bad, null, null, null)))
          .as(bad)
          .isInstanceOf(InvalidGuestDocumentException.class);
    }
    verify(repository, never()).save(any());
  }

  @Test
  void changingOnlyTheTypeChecksTheCurrentNumberAgainstIt() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION, GuestType.CONTRACT, "123")));

    assertThatThrownBy(
            () ->
                service().update(ID, new UpdateCommand(IdDocumentKind.DNI, null, null, null, null)))
        .isInstanceOf(InvalidGuestDocumentException.class);
  }

  @Test
  void aDocumentOfAnotherGuestIsTaken() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any()))
        .thenThrow(new GuestDocumentAlreadyExistsException(new RuntimeException("unique")));

    assertThatThrownBy(
            () -> service().update(ID, new UpdateCommand(null, "87654321", null, null, null)))
        .isInstanceOf(GuestDocumentTakenException.class);
  }

  @Test
  void theStayOfATemporaryGuestIsEdited() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678")));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    accountIs(true, true);

    service()
        .update(
            ID,
            new UpdateCommand(
                null,
                null,
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-20"),
                new BigDecimal("300")));

    var saved = ArgumentCaptor.forClass(Guest.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().stayStartDate()).isEqualTo(LocalDate.parse("2026-10-10"));
    assertThat(saved.getValue().stayEndDate()).isEqualTo(LocalDate.parse("2026-10-20"));
    assertThat(saved.getValue().agreedAmount()).isEqualByComparingTo("300");
  }

  @Test
  void stayDataIsOnlyForTemporaryGuests() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));

    assertThatThrownBy(
            () ->
                service()
                    .update(
                        ID,
                        new UpdateCommand(null, null, LocalDate.parse("2026-10-10"), null, null)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("stayStartDate"));
    assertThatThrownBy(
            () -> service().update(ID, new UpdateCommand(null, null, null, null, BigDecimal.TEN)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("agreedAmount"));
  }

  @Test
  void theEndOfTheStayCannotBeBeforeItsStart() {
    var temporary = guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678");
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(temporary));

    assertThatThrownBy(
            () ->
                service()
                    .update(
                        ID,
                        new UpdateCommand(
                            null,
                            null,
                            LocalDate.parse("2026-10-20"),
                            LocalDate.parse("2026-10-10"),
                            null)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("stayEndDate"));
  }

  @Test
  void theAgreedAmountIsPositiveWithTwoDecimalsAtMostAndFitsTheColumn() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678")));

    for (var bad :
        List.of(
            BigDecimal.ZERO,
            new BigDecimal("-1"),
            new BigDecimal("10.001"),
            new BigDecimal("100000000"))) {
      assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, null, null, null, bad)))
          .as(bad.toPlainString())
          .isInstanceOfSatisfying(
              InvalidGuestStayException.class,
              e -> assertThat(e.field()).isEqualTo("agreedAmount"));
    }
    verify(repository, never()).save(any());
  }
}
