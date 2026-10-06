package com.tarimatwasi.quipu.ambiente.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.ambiente.domain.Environment;
import com.tarimatwasi.quipu.ambiente.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.ambiente.domain.EnvironmentType;
import com.tarimatwasi.quipu.ambiente.port.in.EmptyEnvironmentUpdateException;
import com.tarimatwasi.quipu.ambiente.port.in.EnvironmentCodeTakenException;
import com.tarimatwasi.quipu.ambiente.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.ambiente.port.in.InvalidEnvironmentCodeException;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.CreateCommand;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.EnvironmentKind;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.EnvironmentState;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.EnvironmentView;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.StatusFilter;
import com.tarimatwasi.quipu.ambiente.port.in.ManageEnvironmentsUseCase.UpdateCommand;
import com.tarimatwasi.quipu.ambiente.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.ambiente.port.out.EnvironmentRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {

  private static final UUID ID = UUID.randomUUID();
  private static final Environment ROOM_201 =
      new Environment(ID, "201", EnvironmentType.ROOM, EnvironmentStatus.ACTIVE);
  private static final EnvironmentView VIEW_201 =
      new EnvironmentView(ID, "201", EnvironmentKind.ROOM, EnvironmentState.ACTIVE);

  @Mock EnvironmentRepositoryPort repository;

  private EnvironmentService service() {
    return new EnvironmentService(repository);
  }

  @Test
  void createsWithTheCodeWithoutSurroundingSpaces() {
    when(repository.insert("201", EnvironmentType.ROOM)).thenReturn(ROOM_201);

    var created = service().create(new CreateCommand("  201 ", EnvironmentKind.ROOM));

    assertThat(created).isEqualTo(VIEW_201);
  }

  @Test
  void theViewKeepsTheTypeAndTheStatusOfTheDomain() {
    var cabin = new Environment(ID, "C1", EnvironmentType.CABIN, EnvironmentStatus.INACTIVE);
    when(repository.findById(ID)).thenReturn(Optional.of(cabin));

    assertThat(service().get(ID))
        .isEqualTo(new EnvironmentView(ID, "C1", EnvironmentKind.CABIN, EnvironmentState.INACTIVE));
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
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(" ", null)))
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
    when(repository.update(ID, "202", null)).thenReturn(Optional.of(ROOM_201));

    var updated = service().update(ID, new UpdateCommand(" 202 ", null));

    assertThat(updated).isEqualTo(VIEW_201);
  }

  @Test
  void anUpdateWithoutFieldsIsRejectedBeforeTouchingTheStore() {
    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, null)))
        .isInstanceOf(EmptyEnvironmentUpdateException.class);

    verify(repository, never()).update(any(), any(), any());
  }

  @Test
  void updatingAnUnknownEnvironmentIsNotFound() {
    when(repository.update(eq(ID), any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand(null, EnvironmentKind.CABIN)))
        .isInstanceOf(EnvironmentNotFoundException.class);
  }

  @Test
  void aDuplicateCodeOnUpdateIsReportedAsTaken() {
    when(repository.update(ID, "202", null))
        .thenThrow(new EnvironmentCodeAlreadyExistsException(new RuntimeException()));

    assertThatThrownBy(() -> service().update(ID, new UpdateCommand("202", null)))
        .isInstanceOf(EnvironmentCodeTakenException.class);
  }

  @Test
  void getsAnExistingEnvironmentAndFailsOnAnUnknownOne() {
    when(repository.findById(ID)).thenReturn(Optional.of(ROOM_201));
    when(repository.findById(UUID.fromString("00000000-0000-0000-0000-000000000000")))
        .thenReturn(Optional.empty());

    assertThat(service().get(ID)).isEqualTo(VIEW_201);
    assertThatThrownBy(() -> service().get(UUID.fromString("00000000-0000-0000-0000-000000000000")))
        .isInstanceOf(EnvironmentNotFoundException.class);
  }

  @Test
  void theListingFilterBecomesTheStatusOfTheQuery() {
    when(repository.findAll(EnvironmentStatus.ACTIVE)).thenReturn(List.of(ROOM_201));
    when(repository.findAll(EnvironmentStatus.INACTIVE)).thenReturn(List.of());
    when(repository.findAll(null)).thenReturn(List.of(ROOM_201));

    assertThat(service().list(StatusFilter.ACTIVE)).containsExactly(VIEW_201);
    assertThat(service().list(StatusFilter.INACTIVE)).isEmpty();
    assertThat(service().list(StatusFilter.ALL)).containsExactly(VIEW_201);
  }
}
