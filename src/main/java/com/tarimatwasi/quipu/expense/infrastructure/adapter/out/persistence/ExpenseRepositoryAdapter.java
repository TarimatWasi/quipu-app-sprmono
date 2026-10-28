package com.tarimatwasi.quipu.expense.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.expense.application.port.out.ExpenseRepositoryPort;
import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.MaskedCursor;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class ExpenseRepositoryAdapter implements ExpenseRepositoryPort {

  private final ExpenseJpaRepository jpaRepository;
  private final IdMasker masker;

  ExpenseRepositoryAdapter(ExpenseJpaRepository jpaRepository, IdMasker masker) {
    this.jpaRepository = jpaRepository;
    this.masker = masker;
  }

  @Override
  public Expense insert(
      ExpenseCategory category, BigDecimal amount, YearMonth month, @Nullable String description) {
    return jpaRepository
        .save(new ExpenseJpaEntity(category, amount, month, description))
        .toDomain();
  }

  @Override
  public Optional<Expense> update(
      Long id,
      ExpenseCategory category,
      BigDecimal amount,
      YearMonth month,
      @Nullable String description) {
    return jpaRepository
        .findById(id)
        .map(
            entity -> {
              entity.replace(category, amount, month, description);
              return jpaRepository.save(entity).toDomain();
            });
  }

  @Override
  public boolean delete(Long id) {
    return jpaRepository.deleteByIdIgnoringVersion(id) > 0;
  }

  /**
   * TAR-227: the cursor carries only the id of the last expense, masked in the session that asked,
   * never the internal one nor its date; the date is read again from the expense. A cursor of
   * another session, altered, or of an expense that is no longer there or not of this month is an
   * invalid cursor.
   */
  @Override
  public CursorPage<Expense> findByMonth(YearMonth month, PageQuery page) {
    CreationKey after = page.after() == null ? null : keyOf(page.after(), month);
    Specification<ExpenseJpaEntity> filter =
        (root, query, builder) -> {
          Predicate inMonth = builder.equal(root.get("month"), month.atDay(1));
          if (after == null) {
            return inMonth;
          }
          // (createdDate, id) > (date, id), in the order of the listing.
          Path<Instant> createdDate = root.get("createdDate");
          return builder.and(
              inMonth,
              builder.or(
                  builder.greaterThan(createdDate, after.createdDate()),
                  builder.and(
                      builder.equal(createdDate, after.createdDate()),
                      builder.greaterThan(root.<Long>get("id"), after.id()))));
        };
    Sort order = Sort.by("createdDate", "id");
    // One more than the limit: the extra row only says that there is a next page.
    List<ExpenseJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(
            rows,
            page.limit(),
            row -> MaskedCursor.of(masker, IdKind.EXPENSE, Objects.requireNonNull(row.getId())))
        .map(ExpenseJpaEntity::toDomain);
  }

  @Override
  public BigDecimal sumByMonth(YearMonth month) {
    BigDecimal sum = jpaRepository.sumAmountByMonth(month.atDay(1));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  /** Where the cursor stands: the sort key of its expense, read again from the row. */
  private CreationKey keyOf(String cursor, YearMonth month) {
    Long id = MaskedCursor.idOf(masker, IdKind.EXPENSE, cursor);
    Instant createdDate =
        jpaRepository.findCreatedDate(id, month.atDay(1)).orElseThrow(InvalidCursorException::new);
    return new CreationKey(createdDate, id);
  }

  /** The sort key of an expense. */
  private record CreationKey(Instant createdDate, Long id) {}
}
