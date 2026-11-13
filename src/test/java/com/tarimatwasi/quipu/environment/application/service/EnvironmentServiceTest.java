package com.tarimatwasi.quipu.environment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.environment.application.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.environment.application.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.CreateCommand;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentKind;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentState;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.EnvironmentView;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.StatusFilter;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentOccupancyPort;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentRepositoryPort;
import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {
  private final ChangeSignals changes = org.mockito.Mockito.mock(ChangeSignals.class);

  private static final Long ID = TestIds.next();
  private static final Instant CREATED = Instant.parse("2026-10-01T15:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-01T15:00:00Z"), ZoneId.of("America/Lima"));
  private static final Environment ROOM_201 =
      new Environment(ID, "201", EnvironmentType.ROOM, EnvironmentStatus.ACTIVE, CREATED, null, 0L);
  private static final EnvironmentView VIEW_201 =
      new EnvironmentView(
          ID,
          "201",
          EnvironmentKind.ROOM,
          EnvironmentState.ACTIVE,
          null,
          null,
          LocalDate.of(2026, 10, 1),
          null,
          0L);

  @Mock EnvironmentRepositoryPort repository;
  @Mock EnvironmentOccupancyPort occupancy;

  private EnvironmentService service() {
    return new EnvironmentService(repository, occupancy, CLOCK, changes);
  }

  @Test
  void createsWithTheCodeWithoutSurroundingSpaces() {
    when(repository.insert("201", EnvironmentType.ROOM)).thenReturn(ROOM_201);

    var created = service().create(new CreateCommand("  201 ", EnvironmentKind.ROOM));

    assertThat(created).isEqualTo(VIEW_201);
  }

  @Test
  void theViewKeepsTheTypeAndTheStatusOfTheDomain() {
    Instant deactivated = Instant.parse("2026-10-20T15:00:00Z");
    var cabin =
        new Environment(
            ID, "C1", EnvironmentType.CABIN, EnvironmentStatus.INACTIVE, CREATED, deactivated, 4L);
    when(repository.findById(ID)).thenReturn(Optional.of(cabin));

    assertThat(service().get(ID))
        .isEqualTo(
            new EnvironmentView(
                ID,
                "C1",
                EnvironmentKind.CABIN,
                EnvironmentState.INACTIVE,
                null,
                null,
                LocalDate.of(2026, 10, 1),
                deactivated,
                4L));
  }

  @Test
  void invisibleCharactersMakeTheCodeInvalid() {
    assertThatThrownBy(() -> service().create(new CreateCommand("ㅤ", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("⠀", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("2 01", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("<b>1", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    // TAR-237: only ASCII letters, as the contract fixes it.
    assertThatThrownBy(() -> service().create(new CreateCommand("Cabaña 3", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("ÁTICO 2", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("​", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand("2​01", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(0L, "﻿201", null)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    verify(repository, never()).insert(any(), any());
    verify(repository, never()).update(any(), any(), any());
  }

  @Test
  void aCodeInTheAlphabetIsKeptAsIs() {
    for (var code : new String[] {"201", "C1", "Cabana 3", "A-1/b_2.3", "ATICO 2"}) {
      when(repository.insert(code, EnvironmentType.ROOM)).thenReturn(ROOM_201);

      service().create(new CreateCommand(code, EnvironmentKind.ROOM));

      verify(repository).insert(code, EnvironmentType.ROOM);
    }
  }

  @Test
  void nonBreakingSpacesAtTheEdgesAreNotPartOfTheCode() {
    when(repository.insert("201", EnvironmentType.ROOM)).thenReturn(ROOM_201);

    service().create(new CreateCommand(" 201 ", EnvironmentKind.ROOM));

    verify(repository).insert("201", EnvironmentType.ROOM);
  }

  @Test
  void aCodeThatStripsToNothingIsRejectedOnCreateAndUpdate() {
    assertThatThrownBy(() -> service().create(new CreateCommand("  ", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().create(new CreateCommand(" ", EnvironmentKind.ROOM)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(0L, " ", null)))
        .isInstanceOf(InvalidEnvironmentCodeException.class);

    verify(repository, never()).insert(any(), any());
    verify(repository, never()).update(any(), any(), any());
  }

  @Test
  void aDuplicateCodeOnCreateIsReportedAsTaken() {
    when(repository.insert("201", EnvironmentType.ROOM))
        .thenThrow(new EnvironmentCodeAlreadyExistsException(new RuntimeException()));

    assertThatThrownBy(() -> service().create(new CreateCommand("201", EnvironmentKind.ROOM)))
        .isInstanceOf(EnvironmentCodeTakenException.class);
  }

  @Test
  void updatesOnlyThePresentFieldsAndTrimsTheCode() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ROOM_201));
    when(repository.update(ID, "202", null)).thenReturn(Optional.of(ROOM_201));

    var updated = service().update(ID, new UpdateCommand(0L, " 202 ", null));

    assertThat(updated).isEqualTo(VIEW_201);
  }

  @Test
  void anUpdateWithoutFieldsIsRejectedBeforeTouchingTheStore() {
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(0L, null, null)))
        .isInstanceOf(EmptyEnvironmentUpdateException.class);

    verify(repository, never()).update(any(), any(), any());
  }

  @Test
  void updatingAnUnknownEnvironmentIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().update(ID, new UpdateCommand(0L, null, EnvironmentKind.CABIN)))
        .isInstanceOf(EnvironmentNotFoundException.class);
  }

  @Test
  void aDuplicateCodeOnUpdateIsReportedAsTaken() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ROOM_201));
    when(repository.update(ID, "202", null))
        .thenThrow(new EnvironmentCodeAlreadyExistsException(new RuntimeException()));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(0L, "202", null)))
        .isInstanceOf(EnvironmentCodeTakenException.class);
  }

  @Test
  void getsAnExistingEnvironmentAndFailsOnAnUnknownOne() {
    when(repository.findById(ID)).thenReturn(Optional.of(ROOM_201));
    when(repository.findById(-1L)).thenReturn(Optional.empty());

    assertThat(service().get(ID)).isEqualTo(VIEW_201);
    assertThatThrownBy(() -> service().get(-1L)).isInstanceOf(EnvironmentNotFoundException.class);
  }

  @Test
  void deactivatingAndReactivatingSetTheStatus() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ROOM_201));
    when(repository.updateStatus(eq(ID), eq(EnvironmentStatus.INACTIVE), any())).thenReturn(true);
    when(repository.updateStatus(eq(ID), eq(EnvironmentStatus.ACTIVE), any())).thenReturn(true);

    service().deactivate(ID);
    service().reactivate(ID);

    verify(repository).updateStatus(eq(ID), eq(EnvironmentStatus.INACTIVE), any());
    verify(repository).updateStatus(eq(ID), eq(EnvironmentStatus.ACTIVE), any());
  }

  @Test
  void changingTheStatusOfAnUnknownEnvironmentIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());
    when(repository.updateStatus(eq(ID), any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service().deactivate(ID))
        .isInstanceOf(EnvironmentNotFoundException.class);
    assertThatThrownBy(() -> service().reactivate(ID))
        .isInstanceOf(EnvironmentNotFoundException.class);
  }

  @Test
  void anEnvironmentHeldByAContractCannotBeDeactivated() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ROOM_201));
    when(occupancy.isOccupied(ID)).thenReturn(true);

    assertThatThrownBy(() -> service().deactivate(ID))
        .isInstanceOf(EnvironmentOccupiedException.class);
    verify(repository, never()).updateStatus(any(), any(), any());
  }

  @Test
  void deactivatingAnInactiveEnvironmentChangesNothingEvenIfAContractHoldsIt() {
    var inactive = new Environment(ID, "201", EnvironmentType.ROOM, EnvironmentStatus.INACTIVE);
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(inactive));

    service().deactivate(ID);

    verify(repository, never()).updateStatus(any(), any(), any());
  }

  @Test
  void lockingAnUnknownEnvironmentIsNotFound() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().lock(ID)).isInstanceOf(EnvironmentNotFoundException.class);
  }

  /** TAR-200: the lock tells whether the locked environment is active. */
  @Test
  void lockingAnswersWhetherTheEnvironmentIsActive() {
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(ROOM_201));
    assertThat(service().lock(ID)).isTrue();

    var inactive = new Environment(ID, "201", EnvironmentType.ROOM, EnvironmentStatus.INACTIVE);
    when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(inactive));
    assertThat(service().lock(ID)).isFalse();
  }

  @Test
  void theListingFilterBecomesTheStatusOfTheQuery() {
    PageQuery page = PageQuery.first();
    when(repository.findAll(EnvironmentStatus.ACTIVE, page))
        .thenReturn(new CursorPage<>(List.of(ROOM_201), null));
    when(repository.findAll(EnvironmentStatus.INACTIVE, page))
        .thenReturn(new CursorPage<>(List.of(), null));
    when(repository.findAll(null, page)).thenReturn(new CursorPage<>(List.of(ROOM_201), null));

    assertThat(service().list(StatusFilter.ACTIVE, page, null, null).page().content())
        .containsExactly(VIEW_201);
    assertThat(service().list(StatusFilter.INACTIVE, page, null, null).page().content()).isEmpty();
    assertThat(service().list(StatusFilter.ALL, page, null, null).page().content())
        .containsExactly(VIEW_201);
  }

  @Test
  void theListingKeepsTheCursorOfTheNextPage() {
    PageQuery page = new PageQuery("b64", 1);
    when(repository.findAll(EnvironmentStatus.ACTIVE, page))
        .thenReturn(new CursorPage<>(List.of(ROOM_201), "next"));

    assertThat(service().list(StatusFilter.ACTIVE, page, null, null).page().nextCursor())
        .isEqualTo("next");
  }

  @Test
  void codesOf_mapsEachIdToItsCodeAndAsksNothingForNoIds() {
    when(repository.findAllByIds(java.util.Set.of(ID))).thenReturn(List.of(ROOM_201));

    assertThat(service().codesOf(java.util.Set.of(ID))).isEqualTo(java.util.Map.of(ID, "201"));
    assertThat(service().codesOf(java.util.Set.of())).isEmpty();
    verify(repository, never()).findAllByIds(java.util.Set.of());
  }
}
