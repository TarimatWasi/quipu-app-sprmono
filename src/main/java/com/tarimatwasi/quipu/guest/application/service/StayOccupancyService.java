package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.shared.occupancy.StayOccupancyQuery;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers the contracts and the environments which environments a stay holds (TAR-237). */
@Service
public class StayOccupancyService implements StayOccupancyQuery {

  /** No stay is excluded: the question comes from a contract or an environment. */
  private static final Long NO_STAY = 0L;

  private final StayRepositoryPort stays;

  StayOccupancyService(StayRepositoryPort stays) {
    this.stays = stays;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean environmentTaken(Long environmentId, LocalDate start, @Nullable LocalDate end) {
    return end == null
        ? stays.environmentTakenFrom(environmentId, start)
        : stays.environmentTaken(environmentId, start, end, NO_STAY);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean environmentOccupiedFrom(Long environmentId, LocalDate today) {
    return stays.environmentTakenFrom(environmentId, today);
  }
}
