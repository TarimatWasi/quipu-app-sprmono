package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tarimatwasi.quipu.auth.application.port.in.SessionFingerprintUseCase;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeSignals.Change;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeSignals.Kind;
import com.tarimatwasi.quipu.contract.domain.event.ContractTerminated;
import com.tarimatwasi.quipu.environment.application.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.expense.domain.event.ExpenseDeleted;
import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.guest.domain.event.PersonalDataSaved;
import com.tarimatwasi.quipu.notification.domain.event.NotificationRaised;
import com.tarimatwasi.quipu.shared.events.DomainEvent;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainEventInvalidationsTest {
  private static final EventMetadata META = new EventMetadata(UUID.randomUUID(), Instant.EPOCH, 1L);

  private final ChangeStreamRegistry streams = mock(ChangeStreamRegistry.class);
  private final SessionFingerprintUseCase sessions = mock(SessionFingerprintUseCase.class);
  private final DomainEventInvalidations invalidations =
      new DomainEventInvalidations(streams, sessions, mock(ManageEnvironmentsUseCase.class));

  record Unrelated(EventMetadata metadata) implements DomainEvent {
    @Override
    public String subject() {
      return "other/1";
    }
  }

  /** A new name shows in the history too (DIRECTORY); other personal data only in the record. */
  @Test
  void personalData_isADirectoryChangeOnlyWhenTheNameChanged() {
    assertThat(DomainEventInvalidations.changesOf(new PersonalDataSaved(META, 9L, true)))
        .containsExactly(new Change(Kind.DIRECTORY, 9L, false, null, null));
    assertThat(DomainEventInvalidations.changesOf(new PersonalDataSaved(META, 9L, false)))
        .containsExactly(new Change(Kind.GUEST_RECORD, 9L, false, null, null));
  }

  @Test
  void expense_isAdministrativeOnly() {
    assertThat(DomainEventInvalidations.changesOf(new ExpenseDeleted(META, 4L)))
        .containsExactly(new Change(Kind.EXPENSE, null, false, null, null));
  }

  @Test
  void fine_keepsItsGuestForTheAdministrativeDetail() {
    var fine = new FineApplied(META, 4L, 9L, BigDecimal.TEN, YearMonth.of(2026, 10));

    assertThat(DomainEventInvalidations.changesOf(fine))
        .containsExactly(new Change(Kind.FINE, 9L, false, null, null));
  }

  @Test
  void contract_reachesItsGuestAndEnvironment() {
    assertThat(DomainEventInvalidations.changesOf(new ContractTerminated(META, 4L, 9L, 3L)))
        .containsExactly(new Change(Kind.CONTRACT, 9L, true, null, 3L));
  }

  @Test
  void notification_ofTheAdministrators_isNotRoutedToAGuest() {
    assertThat(DomainEventInvalidations.changesOf(new NotificationRaised(META, 4L, true, 9L)))
        .containsExactly(new Change(Kind.NOTIFICATION, null, false, null, null));
    assertThat(DomainEventInvalidations.changesOf(new NotificationRaised(META, 5L, false, 9L)))
        .containsExactly(new Change(Kind.NOTIFICATION, 9L, false, null, null));
  }

  @Test
  void deliversOnlyFactsThatTouchAnOpenScreen() {
    invalidations.on(new Unrelated(META));
    verifyNoInteractions(streams);

    invalidations.on(new ExpenseDeleted(META, 4L));
    verify(streams).changed(new Change(Kind.EXPENSE, null, false, null, null));
  }
}
