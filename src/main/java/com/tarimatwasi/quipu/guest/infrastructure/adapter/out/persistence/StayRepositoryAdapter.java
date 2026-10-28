package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.MaskedCursor;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class StayRepositoryAdapter implements StayRepositoryPort {

  private final StayJpaRepository jpaRepository;
  private final IdMasker masker;

  StayRepositoryAdapter(StayJpaRepository jpaRepository, IdMasker masker) {
    this.jpaRepository = jpaRepository;
    this.masker = masker;
  }

  /**
   * TAR-227: the cursor carries only the id of the last stay, masked in the session that asked,
   * never the internal one nor its start date; the date is read again from the stay. A cursor of
   * another session, altered, or of a stay that is no longer there or of another guest is an
   * invalid cursor.
   */
  @Override
  public CursorPage<Stay> findByGuest(Long guestId, PageQuery page) {
    StartKey after = page.after() == null ? null : keyOf(page.after(), guestId);
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
        stay -> MaskedCursor.of(masker, IdKind.STAY, stay.id()));
  }

  @Override
  public List<Stay> findAllInForceOn(LocalDate day) {
    return jpaRepository
        .findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByIdAsc(day, day)
        .stream()
        .map(StayJpaEntity::toDomain)
        .toList();
  }

  @Override
  public List<Long> findIdsStartingBetween(LocalDate first, LocalDate last) {
    return jpaRepository.findIdsByStartDateBetween(first, last);
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

  /** Where the cursor stands: the sort key of its stay, read again from the row. */
  private StartKey keyOf(String cursor, Long guestId) {
    Long id = MaskedCursor.idOf(masker, IdKind.STAY, cursor);
    LocalDate startDate =
        jpaRepository.findStartDate(id, guestId).orElseThrow(InvalidCursorException::new);
    return new StartKey(startDate, id);
  }

  /** The sort key of a stay. */
  private record StartKey(LocalDate startDate, Long id) {}
}
