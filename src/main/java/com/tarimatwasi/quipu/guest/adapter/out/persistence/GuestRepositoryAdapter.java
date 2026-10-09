package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class GuestRepositoryAdapter implements GuestRepositoryPort {

  private final GuestJpaRepository jpaRepository;

  GuestRepositoryAdapter(GuestJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Optional<Guest> findById(Long id) {
    return jpaRepository.findById(id).map(GuestJpaEntity::toDomain);
  }

  @Override
  public Optional<Guest> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(GuestJpaEntity::toDomain);
  }

  @Override
  public List<Guest> findAll(@Nullable Collection<GuestStatus> statuses, @Nullable GuestType type) {
    List<GuestJpaEntity> rows;
    if (statuses == null) {
      rows =
          type == null
              ? jpaRepository.findAllByOrderByDocumentTypeAscDocumentNumberAsc()
              : jpaRepository.findByTypeOrderByDocumentTypeAscDocumentNumberAsc(type);
    } else {
      rows =
          type == null
              ? jpaRepository.findByStatusInOrderByDocumentTypeAscDocumentNumberAsc(statuses)
              : jpaRepository.findByStatusInAndTypeOrderByDocumentTypeAscDocumentNumberAsc(
                  statuses, type);
    }
    return rows.stream().map(GuestJpaEntity::toDomain).toList();
  }

  /** Flushes so that the unique violation of two simultaneous writes surfaces here, not later. */
  @Override
  public Guest save(Guest guest) {
    GuestJpaEntity entity =
        jpaRepository
            .findWithLockById(guest.id())
            .orElseThrow(() -> new NoSuchElementException("guest " + guest.id()));
    entity.apply(guest);
    try {
      return jpaRepository.saveAndFlush(entity).toDomain();
    } catch (DataIntegrityViolationException e) {
      throw new GuestDocumentAlreadyExistsException(e);
    }
  }
}
