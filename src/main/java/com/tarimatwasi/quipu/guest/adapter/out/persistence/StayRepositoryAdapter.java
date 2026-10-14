package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.guest.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class StayRepositoryAdapter implements StayRepositoryPort {

  private final StayJpaRepository jpaRepository;

  StayRepositoryAdapter(StayJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public CursorPage<Stay> findByGuest(Long guestId, PageQuery page) {
    StartKey after = page.after() == null ? null : StartKey.decode(page.after());
    Specification<StayJpaEntity> filter =
        (root, query, builder) -> {
          Predicate ofGuest = builder.equal(root.get("guestId"), guestId);
          if (after == null) {
            return ofGuest;
          }
          // (startDate, id) < (date, id), in the order of the listing: newest first.
          Path<LocalDate> startDate = root.get("startDate");
          return builder.and(
              ofGuest,
              builder.or(
                  builder.lessThan(startDate, after.startDate()),
                  builder.and(
                      builder.equal(startDate, after.startDate()),
                      builder.lessThan(root.<Long>get("id"), after.id()))));
        };
    Sort order = Sort.by(Sort.Order.desc("startDate"), Sort.Order.desc("id"));
    // One more than the limit: the extra row only says that there is a next page.
    List<StayJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(
        rows.stream().map(StayJpaEntity::toDomain).toList(),
        page.limit(),
        stay -> CursorToken.encode(stay.startDate().toString(), stay.id().toString()));
  }

  @Override
  public List<Stay> findAllByGuest(Long guestId) {
    return jpaRepository.findByGuestId(guestId).stream().map(StayJpaEntity::toDomain).toList();
  }

  @Override
  public Optional<Stay> findByIdAndGuest(Long id, Long guestId) {
    return jpaRepository.findByIdAndGuestId(id, guestId).map(StayJpaEntity::toDomain);
  }

  @Override
  public Stay insert(
      Long guestId, LocalDate startDate, LocalDate endDate, BigDecimal agreedAmount) {
    return jpaRepository
        .saveAndFlush(new StayJpaEntity(guestId, startDate, endDate, agreedAmount))
        .toDomain();
  }

  @Override
  public Stay update(Stay stay) {
    StayJpaEntity entity =
        jpaRepository
            .findById(stay.id())
            .orElseThrow(() -> new NoSuchElementException("stay " + stay.id()));
    entity.apply(stay);
    return jpaRepository.saveAndFlush(entity).toDomain();
  }

  /** The sort key of a stay, as the cursor carries it. */
  private record StartKey(LocalDate startDate, Long id) {

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 9999;

    static StartKey decode(String cursor) {
      List<String> parts = CursorToken.decode(cursor, 2);
      try {
        LocalDate startDate = LocalDate.parse(parts.get(0));
        // Java parses years far outside what a PostgreSQL date holds; that would be a 500.
        if (startDate.getYear() < MIN_YEAR || startDate.getYear() > MAX_YEAR) {
          throw new InvalidCursorException();
        }
        return new StartKey(startDate, Long.valueOf(parts.get(1)));
      } catch (DateTimeParseException | NumberFormatException notAKey) {
        throw new InvalidCursorException();
      }
    }
  }
}
