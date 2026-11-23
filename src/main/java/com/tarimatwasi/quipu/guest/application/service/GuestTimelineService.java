package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestTimelineUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.Stay;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs on the outbox delivery of the business day, as the system (SystemActor). */
@Service
public class GuestTimelineService implements GuestTimelineUseCase {

  /** A stay ending today, tomorrow or the day after is ending soon. */
  static final int STAY_ENDING_DAYS = 2;

  private final StayRepositoryPort stays;
  private final GuestRepositoryPort guests;
  private final GuestAccountPort accounts;
  private final GuestTimeFacts facts;
  private final Clock clock;

  GuestTimelineService(
      StayRepositoryPort stays,
      GuestRepositoryPort guests,
      GuestAccountPort accounts,
      GuestTimeFacts facts,
      Clock clock) {
    this.stays = stays;
    this.guests = guests;
    this.accounts = accounts;
    this.facts = facts;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void sweep(LocalDate day) {
    for (Stay stay : stays.findAllInForceOn(day)) {
      if (ChronoUnit.DAYS.between(day, stay.endDate()) <= STAY_ENDING_DAYS) {
        facts.stayEnding(stay.id(), stay.guestId(), stay.environmentId(), stay.endDate());
      }
    }
    List<Guest> pending = guests.findAllMatching(List.of(GuestStatus.PENDING_ACTIVATION), null);
    if (pending.isEmpty()) {
      return;
    }
    Set<Long> ids = pending.stream().map(Guest::id).collect(Collectors.toSet());
    Instant now = clock.instant();
    for (Map.Entry<Long, Instant> expiry : accounts.unusedAccessExpiries(ids).entrySet()) {
      if (!expiry.getValue().isAfter(now)) {
        facts.accessExpired(expiry.getKey(), expiry.getValue());
      }
    }
  }
}
