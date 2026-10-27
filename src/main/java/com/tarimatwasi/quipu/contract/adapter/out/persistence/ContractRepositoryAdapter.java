package com.tarimatwasi.quipu.contract.adapter.out.persistence;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class ContractRepositoryAdapter implements ContractRepositoryPort {

  private final ContractJpaRepository jpaRepository;

  ContractRepositoryAdapter(ContractJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Contract insert(Draft draft) {
    return jpaRepository.saveAndFlush(new ContractJpaEntity(draft)).toDomain();
  }

  @Override
  public Optional<Contract> findById(Long id) {
    return jpaRepository.findById(id).map(ContractJpaEntity::toDomain);
  }

  @Override
  public Optional<Contract> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(ContractJpaEntity::toDomain);
  }

  @Override
  public List<Contract> findAllByGuest(Long guestId) {
    return jpaRepository.findByGuestIdOrderByStartDateAscIdAsc(guestId).stream()
        .map(ContractJpaEntity::toDomain)
        .toList();
  }

  @Override
  public List<Contract> findAllByGuestForUpdate(Long guestId) {
    return jpaRepository.findWithLockByGuestIdOrderByIdAsc(guestId).stream()
        .map(ContractJpaEntity::toDomain)
        .toList();
  }

  @Override
  public List<Contract> findAllByIds(Collection<Long> ids) {
    return jpaRepository.findAllById(ids).stream().map(ContractJpaEntity::toDomain).toList();
  }

  @Override
  public Optional<Contract> findLatestByGuest(Long guestId) {
    return jpaRepository
        .findFirstByGuestIdOrderByCreatedDateDescIdDesc(guestId)
        .map(ContractJpaEntity::toDomain);
  }

  @Override
  public CursorPage<Contract> findAll(
      @Nullable ContractStatus status, LocalDate today, PageQuery page) {
    CreationKey after = page.after() == null ? null : CreationKey.decode(page.after());
    Specification<ContractJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (status != null) {
            where.add(hasStatus(status, today, root, builder));
          }
          if (after != null) {
            // (createdDate, id) < (date, id), in the order of the listing: newest first.
            Path<LocalDateTime> createdDate = root.get("createdDate");
            where.add(
                builder.or(
                    builder.lessThan(createdDate, after.createdDate()),
                    builder.and(
                        builder.equal(createdDate, after.createdDate()),
                        builder.lessThan(root.<Long>get("id"), after.id()))));
          }
          return builder.and(where.toArray(Predicate[]::new));
        };
    Sort order = Sort.by(Sort.Order.desc("createdDate"), Sort.Order.desc("id"));
    // One more than the limit: the extra row only says that there is a next page.
    List<ContractJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(rows, page.limit(), ContractRepositoryAdapter::cursorOf)
        .map(ContractJpaEntity::toDomain);
  }

  @Override
  public boolean existsInForce(Long environmentId, LocalDate today) {
    return jpaRepository.exists(inForce(environmentId, today));
  }

  @Override
  public List<Contract> findAllInForce(Long environmentId, LocalDate today) {
    Sort order = Sort.by(Sort.Order.asc("startDate"), Sort.Order.asc("id"));
    return jpaRepository.findAll(inForce(environmentId, today), order).stream()
        .map(ContractJpaEntity::toDomain)
        .toList();
  }

  /** The contracts of the environment that are ACTIVE on today. */
  private static Specification<ContractJpaEntity> inForce(Long environmentId, LocalDate today) {
    return (root, query, builder) ->
        builder.and(
            builder.equal(root.get("environmentId"), environmentId),
            hasStatus(ContractStatus.ACTIVE, today, root, builder));
  }

  @Override
  public Contract save(Contract contract) {
    ContractJpaEntity entity =
        jpaRepository
            .findById(contract.id())
            .orElseThrow(() -> new NoSuchElementException("contract " + contract.id()));
    entity.apply(contract);
    return jpaRepository.saveAndFlush(entity).toDomain();
  }

  /** The state a contract has on today: EXPIRED is not stored, it comes from the end date. */
  private static Predicate hasStatus(
      ContractStatus status,
      LocalDate today,
      Root<ContractJpaEntity> root,
      CriteriaBuilder builder) {
    Predicate terminated = builder.equal(root.get("status"), ContractJpaEntity.TERMINATED);
    Path<LocalDate> endDate = root.get("endDate");
    return switch (status) {
      case TERMINATED -> terminated;
      case ACTIVE ->
          builder.and(
              builder.not(terminated),
              builder.or(endDate.isNull(), builder.greaterThanOrEqualTo(endDate, today)));
      case EXPIRED -> builder.and(builder.not(terminated), builder.lessThan(endDate, today));
    };
  }

  private static String cursorOf(ContractJpaEntity contract) {
    return CursorToken.encode(
        // PostgreSQL keeps microseconds: a finer value would sort after the row it came from.
        contract.getCreatedDate().orElseThrow().truncatedTo(ChronoUnit.MICROS).toString(),
        Objects.requireNonNull(contract.getId()).toString());
  }

  /** The sort key of a contract, as the cursor carries it. */
  private record CreationKey(LocalDateTime createdDate, Long id) {

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 9999;

    static CreationKey decode(String cursor) {
      List<String> parts = CursorToken.decode(cursor, 2);
      try {
        LocalDateTime createdDate = LocalDateTime.parse(parts.get(0));
        // Java parses years far outside what a PostgreSQL timestamp holds; that would be a 500.
        if (createdDate.getYear() < MIN_YEAR || createdDate.getYear() > MAX_YEAR) {
          throw new InvalidCursorException();
        }
        return new CreationKey(createdDate, Long.valueOf(parts.get(1)));
      } catch (DateTimeParseException | NumberFormatException notAKey) {
        throw new InvalidCursorException();
      }
    }
  }
}
