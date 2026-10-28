package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentLockedException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestHasActiveContractException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidGuestDocumentException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.IdDocumentKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.OnboardingProgress;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.StatusFilter;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.debt.GuestDebtQuery;
import com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException;
import com.tarimatwasi.quipu.shared.occupancy.ContractOccupancyQuery;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));

  @Mock GuestRepositoryPort repository;
  @Mock GuestAccountPort accounts;
  @Mock StayRepositoryPort stays;
  @Mock ContractOccupancyQuery contracts;
  @Mock GuestDebtQuery debts;
  @Mock OnboardingRepositoryPort onboarding;

  private GuestService service() {
    return new GuestService(repository, accounts, stays, contracts, debts, onboarding, CLOCK);
  }

  private static Guest guest(GuestStatus status) {
    return guest(status, GuestType.CONTRACT, "12345678");
  }

  private static Guest guest(GuestStatus status, GuestType type, String number) {
    return new Guest(
        ID, DocumentType.DNI, number, null, null, null, type, status, null, null, null, null);
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

  private static CursorPage<Guest> pageOf(Guest... guests) {
    return new CursorPage<>(List.of(guests), null);
  }

  @Test
  void currentHidesOnlyTheInactiveGuests() {
    PageQuery page = PageQuery.first();
    when(repository.findAll(
            List.of(GuestStatus.PENDING_ACTIVATION, GuestStatus.ONBOARDING, GuestStatus.ACTIVE),
            null,
            page))
        .thenReturn(pageOf(guest(GuestStatus.PENDING_ACTIVATION)));
    noAccount();

    var list = service().list(StatusFilter.CURRENT, null, page).content();

    assertThat(list).hasSize(1);
    assertThat(list.get(0).status()).isEqualTo(GuestState.PENDING_ACTIVATION);
    assertThat(list.get(0).hasLoginAccess()).isFalse();
    assertThat(list.get(0).name()).isNull();
  }

  @Test
  void allDoesNotFilterByStatusAndTheTypeIsPassedOn() {
    PageQuery page = PageQuery.first();
    when(repository.findAll(null, GuestType.TEMPORARY, page)).thenReturn(pageOf());

    assertThat(service().list(StatusFilter.ALL, GuestKind.TEMPORARY, page).content()).isEmpty();
  }

  @Test
  void aSingleStatusFilterAsksForThatStatus() {
    PageQuery page = PageQuery.first();
    when(repository.findAll(List.of(GuestStatus.INACTIVE), null, page)).thenReturn(pageOf());

    assertThat(service().list(StatusFilter.INACTIVE, null, page).content()).isEmpty();
  }

  @Test
  void aGuestWhoseAccountCanLogInHasLoginAccess() {
    PageQuery page = PageQuery.first();
    when(repository.findAll(null, null, page)).thenReturn(pageOf(guest(GuestStatus.ACTIVE)));
    accountIs(true, true);

    assertThat(service().list(StatusFilter.ALL, null, page).content().get(0).hasLoginAccess())
        .isTrue();
  }

  @Test
  void theListingKeepsTheCursorOfTheNextPage() {
    PageQuery page = new PageQuery("b64", 1);
    when(repository.findAll(null, null, page))
        .thenReturn(new CursorPage<>(List.of(guest(GuestStatus.ACTIVE)), "next"));
    accountIs(true, true);

    assertThat(service().list(StatusFilter.ALL, null, page).nextCursor()).isEqualTo("next");
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
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, null)))
        .isInstanceOf(EmptyGuestUpdateException.class);
    verify(repository, never()).findByIdForUpdate(any());
  }

  @Test
  void updatingAnUnknownGuestIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, "87654321")))
        .isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void theDocumentIsCorrectedWhileThePendingGuestHasNotActivated() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    noAccount();

    var summary = service().update(ID, new UpdateCommand(null, " 87654321 "));

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

    var summary = service().update(ID, new UpdateCommand(IdDocumentKind.PASSPORT, "ab123456"));

    assertThat(summary.documentType()).isEqualTo(IdDocumentKind.PASSPORT);
    assertThat(summary.documentNumber()).isEqualTo("AB123456");
  }

  @Test
  void theDocumentCannotChangeOnceTheGuestStartedTheOnboarding() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ONBOARDING)));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, "87654321")))
        .isInstanceOf(GuestDocumentLockedException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void sendingTheSameDocumentAgainIsNotAChangeEvenForAnActiveGuest() {
    var active = guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678");
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(active));
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    accountIs(true, true);

    var summary = service().update(ID, new UpdateCommand(IdDocumentKind.DNI, "12345678"));

    assertThat(summary.documentNumber()).isEqualTo("12345678");
    assertThat(summary.hasLoginAccess()).isTrue();
  }

  @Test
  void aDniNeedsEightDigits() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));

    for (var bad : List.of("1234567", "123456789", "1234567A", "", "   ")) {
      assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, bad)))
          .as(bad)
          .isInstanceOf(InvalidGuestDocumentException.class);
    }
    verify(repository, never()).save(any());
  }

  @Test
  void changingOnlyTheTypeChecksTheCurrentNumberAgainstIt() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION, GuestType.CONTRACT, "123")));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(IdDocumentKind.DNI, null)))
        .isInstanceOf(InvalidGuestDocumentException.class);
  }

  @Test
  void aDocumentOfAnotherGuestIsTaken() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any()))
        .thenThrow(new GuestDocumentAlreadyExistsException(new RuntimeException("unique")));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, "87654321")))
        .isInstanceOf(GuestDocumentTakenException.class);
  }

  // --- the stay a guest shows (deprecated read-only fields of the detail) ---

  private static Stay stay(String start, String end, String amount) {
    return new Stay(
        TestIds.next(), ID, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(amount));
  }

  @Test
  void aDocumentOfAnAdministratorIsTakenAndNothingIsSaved() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(accounts.documentBelongsToAdministrator("DNI", "87654321")).thenReturn(true);

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, "87654321")))
        .isInstanceOf(GuestDocumentTakenException.class);

    verify(repository, never()).save(any());
  }

  @Test
  void aContactEmailOfAnotherGuestIsTaken() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(guest(GuestStatus.PENDING_ACTIVATION)));
    when(repository.save(any()))
        .thenThrow(new GuestEmailAlreadyExistsException(new RuntimeException("unique")));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, "87654321")))
        .isInstanceOf(GuestEmailTakenException.class);
  }

  @Test
  void theDetailShowsTheStayInCourseToday() {
    when(repository.findById(ID))
        .thenReturn(Optional.of(guest(GuestStatus.ACTIVE, GuestType.TEMPORARY, "12345678")));
    when(stays.findAllByGuest(ID))
        .thenReturn(
            List.of(
                stay("2026-09-01", "2026-09-10", "100"),
                stay("2026-10-01", "2026-10-06", "200"),
                stay("2026-11-01", "2026-11-10", "300")));
    accountIs(true, true);

    var detail = service().get(ID);

    assertThat(detail.stayStartDate()).isEqualTo(LocalDate.parse("2026-10-01"));
    assertThat(detail.stayEndDate()).isEqualTo(LocalDate.parse("2026-10-06"));
    assertThat(detail.agreedAmount()).isEqualByComparingTo("200");
  }

  @Test
  void theDetailShowsNoStayForAGuestWithoutStays() {
    when(repository.findById(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    accountIs(true, true);

    var detail = service().get(ID);

    assertThat(detail.stayStartDate()).isNull();
    assertThat(detail.stayEndDate()).isNull();
    assertThat(detail.agreedAmount()).isNull();
  }

  @Test
  void lockingAnUnknownGuestIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().lock(ID)).isInstanceOf(GuestNotFoundException.class);
  }

  // --- deactivate and reactivate (RF-13, RN-12) ---

  @Test
  void deactivatingKeepsTheStateItHadAndDisablesTheAccount() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ONBOARDING)));
    when(contracts.guestContractInForce(ID)).thenReturn(Optional.empty());

    service().deactivate(ID);

    ArgumentCaptor<Guest> saved = ArgumentCaptor.forClass(Guest.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(GuestStatus.INACTIVE);
    assertThat(saved.getValue().statusBeforeInactive()).isEqualTo(GuestStatus.ONBOARDING);
    verify(accounts).setActive(ID, false);
  }

  @Test
  void aGuestWithAContractInForceIsNotDeactivatedAndTheAnswerNamesTheContract() {
    Long contractId = TestIds.next();
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    when(contracts.guestContractInForce(ID)).thenReturn(Optional.of(contractId));

    assertThatThrownBy(() -> service().deactivate(ID))
        .isInstanceOfSatisfying(
            GuestHasActiveContractException.class,
            e -> assertThat(e.contractId()).isEqualTo(contractId));
    verify(repository, never()).save(any());
    verify(accounts, never()).setActive(any(), anyBoolean());
  }

  @Test
  void theDebtDoesNotStopTheDeactivation() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    when(contracts.guestContractInForce(ID)).thenReturn(Optional.empty());

    service().deactivate(ID);

    verify(debts, never()).owedPeriods(any());
    verify(accounts).setActive(ID, false);
  }

  @Test
  void deactivatingAnInactiveGuestAgainChangesNothing() {
    when(repository.findByIdForUpdate(ID))
        .thenReturn(Optional.of(inactiveFrom(GuestStatus.ACTIVE, guest(GuestStatus.ACTIVE))));

    service().deactivate(ID);

    verify(repository, never()).save(any());
    verify(contracts, never()).guestContractInForce(any());
    verify(accounts, never()).setActive(any(), anyBoolean());
  }

  @Test
  void deactivatingOrReactivatingAnUnknownGuestIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().deactivate(ID)).isInstanceOf(GuestNotFoundException.class);
    assertThatThrownBy(() -> service().reactivate(ID)).isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void reactivatingRestoresTheStateItHadAndTheAccount() {
    Guest inactive = inactiveFrom(GuestStatus.ONBOARDING, guest(GuestStatus.ONBOARDING));
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(inactive));
    when(debts.owedPeriods(ID)).thenReturn(List.of());
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    accountIs(true, true);

    GuestSummaryView view = service().reactivate(ID);

    ArgumentCaptor<Guest> saved = ArgumentCaptor.forClass(Guest.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(GuestStatus.ONBOARDING);
    assertThat(saved.getValue().statusBeforeInactive()).isNull();
    verify(accounts).setActive(ID, true);
    assertThat(view.status()).isEqualTo(GuestState.ONBOARDING);
    assertThat(view.hasLoginAccess()).isTrue();
  }

  @Test
  void aGuestPendingActivationComesBackPendingActivation() {
    Guest inactive =
        inactiveFrom(GuestStatus.PENDING_ACTIVATION, guest(GuestStatus.PENDING_ACTIVATION));
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(inactive));
    when(debts.owedPeriods(ID)).thenReturn(List.of());
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    noAccount();

    assertThat(service().reactivate(ID).status()).isEqualTo(GuestState.PENDING_ACTIVATION);
  }

  @Test
  void aGuestThatOwesPeriodsIsNotReactivated() {
    Guest inactive = inactiveFrom(GuestStatus.ACTIVE, guest(GuestStatus.ACTIVE));
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(inactive));
    when(debts.owedPeriods(ID)).thenReturn(List.of(YearMonth.of(2026, 9)));

    assertThatThrownBy(() -> service().reactivate(ID))
        .isInstanceOfSatisfying(
            GuestHasPendingDebtException.class,
            e -> assertThat(e.periods()).containsExactly(YearMonth.of(2026, 9)));
    verify(repository, never()).save(any());
    verify(accounts, never()).setActive(any(), anyBoolean());
  }

  @Test
  void reactivatingAGuestThatIsNotInactiveAnswersItAsItIs() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(guest(GuestStatus.ACTIVE)));
    accountIs(true, true);

    GuestSummaryView view = service().reactivate(ID);

    assertThat(view.status()).isEqualTo(GuestState.ACTIVE);
    verify(repository, never()).save(any());
    verify(debts, never()).owedPeriods(any());
    verify(accounts, never()).setActive(any(), anyBoolean());
  }
}
