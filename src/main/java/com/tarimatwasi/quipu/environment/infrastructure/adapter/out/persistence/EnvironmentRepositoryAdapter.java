package com.tarimatwasi.quipu.environment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentCodeAlreadyExistsException;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentRepositoryPort;
import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
  public Optional<Environment> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(EnvironmentJpaEntity::toDomain);
  }

  @Override
  public List<Environment> findAllByIds(Collection<Long> ids) {
    return jpaRepository.findAllById(ids).stream().map(EnvironmentJpaEntity::toDomain).toList();
  }

  @Override
  public CursorPage<Environment> findAll(@Nullable EnvironmentStatus status, PageQuery page) {
    String after = page.after() == null ? null : CursorToken.decode(page.after(), 1).get(0);
    Specification<EnvironmentJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (status != null) {
            where.add(builder.equal(root.get("status"), status));
          }
          if (after != null) {
            where.add(builder.greaterThan(root.<String>get("code"), after));
          }
          return builder.and(where.toArray(Predicate[]::new));
        };
    // One more than the limit: the extra row only says that there is a next page.
    List<EnvironmentJpaEntity> rows =
        jpaRepository.findBy(
            filter, query -> query.sortBy(Sort.by("code")).limit(page.limit() + 1).all());
    return CursorPage.of(
        rows.stream().map(EnvironmentJpaEntity::toDomain).toList(),
        page.limit(),
        environment -> CursorToken.encode(environment.code()));
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
