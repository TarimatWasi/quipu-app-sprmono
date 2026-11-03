package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.occupancy.StayOccupancyQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
  public Optional<Occupancy> stayCrossing(
      Long environmentId, LocalDate start, @Nullable LocalDate end) {
    return Occupancy.firstCrossing(occupanciesOf(List.of(environmentId)), start, end);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean environmentOccupiedFrom(Long environmentId, LocalDate today) {
    return stays.environmentTakenFrom(environmentId, today);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Occupancy> occupanciesOf(Collection<Long> environmentIds) {
    List<Occupancy> occupancies = new ArrayList<>();
    for (Stay stay : stays.findAllByEnvironments(environmentIds)) {
      Long environmentId = stay.environmentId();
      if (environmentId != null) {
        occupancies.add(
            new Occupancy(
                environmentId,
                Occupancy.Kind.STAY,
                stay.guestId(),
                null,
                stay.id(),
                stay.startDate(),
                stay.endDate()));
      }
    }
    return occupancies;
  }
}
