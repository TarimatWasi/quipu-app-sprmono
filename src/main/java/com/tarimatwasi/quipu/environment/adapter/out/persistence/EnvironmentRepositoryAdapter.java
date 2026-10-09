package com.tarimatwasi.quipu.environment.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.port.out.EnvironmentRepositoryPort;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentRepositoryAdapter implements EnvironmentRepositoryPort {

  private final EnvironmentJpaRepository jpaRepository;

  EnvironmentRepositoryAdapter(EnvironmentJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Environment insert(String code, EnvironmentType type) {
    return saveAndFlush(new EnvironmentJpaEntity(code, type)).toDomain();
  }

  @Override
  public Optional<Environment> findById(Long id) {
    return jpaRepository.findById(id).map(EnvironmentJpaEntity::toDomain);
  }

  @Override
  public List<Environment> findAll(@Nullable EnvironmentStatus status) {
    var rows =
        status == null
            ? jpaRepository.findAllByOrderByCodeAsc()
            : jpaRepository.findByStatusOrderByCodeAsc(status);
    return rows.stream().map(EnvironmentJpaEntity::toDomain).toList();
  }

  @Override
  public Optional<Environment> update(
      Long id, @Nullable String code, @Nullable EnvironmentType type) {
    return jpaRepository
        .findWithLockById(id)
        .map(
            entity -> {
              if (code != null) {
                entity.rename(code);
              }
              if (type != null) {
                entity.retype(type);
              }
              return saveAndFlush(entity).toDomain();
            });
  }

  @Override
  public boolean updateStatus(Long id, EnvironmentStatus status) {
    var entity = jpaRepository.findWithLockById(id);
    entity.ifPresent(found -> found.changeStatus(status));
    return entity.isPresent();
  }

  /** Flushes so that the unique violation of two simultaneous writes surfaces here, not later. */
  private EnvironmentJpaEntity saveAndFlush(EnvironmentJpaEntity entity) {
    try {
      return jpaRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      throw new EnvironmentCodeAlreadyExistsException(e);
    }
  }
}
