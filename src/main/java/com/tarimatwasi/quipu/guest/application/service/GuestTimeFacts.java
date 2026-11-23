package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.domain.event.AccessExpired;
import com.tarimatwasi.quipu.guest.domain.event.StayEnding;
import com.tarimatwasi.quipu.shared.events.PublishEvent;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The facts time alone makes true about guests; each method states one, once per id. */
@Service
public class GuestTimeFacts {

  /** The stay ends soon; a new end date is a new fact. */
  @Transactional
  @PublishEvent(
      type = StayEnding.class,
      id = "#stayId + ':' + #endDate",
      args = {"#stayId", "#guestId", "#environmentId", "#endDate"})
  public void stayEnding(
      Long stayId, Long guestId, @Nullable Long environmentId, LocalDate endDate) {
    // The fact is the whole change.
  }

  /** The access expired unused; a new access that expires again is a new fact. */
  @Transactional
  @PublishEvent(
      type = AccessExpired.class,
      id = "#guestId + ':' + #expiredAt",
      args = {"#guestId", "#expiredAt"})
  public void accessExpired(Long guestId, Instant expiredAt) {
    // The fact is the whole change.
  }
}
