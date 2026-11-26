package com.tarimatwasi.quipu.guest.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestTimelineServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

  private final StayRepositoryPort stays = mock(StayRepositoryPort.class);
  private final GuestRepositoryPort guests = mock(GuestRepositoryPort.class);
  private final GuestAccountPort accounts = mock(GuestAccountPort.class);
  private final GuestTimeFacts facts = mock(GuestTimeFacts.class);
  private final GuestTimelineService timeline =
      new GuestTimelineService(stays, guests, accounts, facts, Clock.fixed(NOW, ZoneOffset.UTC));

  /** Only an access whose time is over is stated as expired; one still running is not. */
  @Test
  void onlyTheAccessesThatAreOverAreStatedExpired() {
    when(guests.findAllMatching(List.of(GuestStatus.PENDING_ACTIVATION), null))
        .thenReturn(List.of(pending(5L), pending(6L), pending(7L)));
    when(accounts.unusedAccessExpiries(Set.of(5L, 6L, 7L)))
        .thenReturn(Map.of(5L, NOW.minusSeconds(1), 6L, NOW, 7L, NOW.plusSeconds(1)));

    timeline.sweep(LocalDate.of(2026, 10, 14));

    verify(facts).accessExpired(5L, NOW.minusSeconds(1));
    verify(facts).accessExpired(6L, NOW);
    verify(facts, never()).accessExpired(7L, NOW.plusSeconds(1));
  }

  @Test
  void withoutGuestsPendingNothingIsAskedOfTheAccounts() {
    when(guests.findAllMatching(List.of(GuestStatus.PENDING_ACTIVATION), null))
        .thenReturn(List.of());

    timeline.sweep(LocalDate.of(2026, 10, 14));

    verify(accounts, never()).unusedAccessExpiries(any());
    verify(facts, never()).accessExpired(anyLong(), any());
  }

  private static Guest pending(Long id) {
    return new Guest(
        id,
        DocumentType.DNI,
        "1234567" + id,
        null,
        null,
        GuestType.CONTRACT,
        GuestStatus.PENDING_ACTIVATION,
        null,
        null,
        null,
        null);
  }
}
