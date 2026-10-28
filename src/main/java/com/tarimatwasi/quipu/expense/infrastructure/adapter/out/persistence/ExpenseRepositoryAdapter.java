package com.tarimatwasi.quipu.expense.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.expense.application.port.out.ExpenseRepositoryPort;
import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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

  ExpenseRepositoryAdapter(ExpenseJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
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

  @Override
  public CursorPage<Expense> findByMonth(YearMonth month, PageQuery page) {
    CreationKey after = page.after() == null ? null : CreationKey.decode(page.after());
    Specification<ExpenseJpaEntity> filter =
        (root, query, builder) -> {
          Predicate inMonth = builder.equal(root.get("month"), month.atDay(1));
          if (after == null) {
            return inMonth;
          }
          // (createdDate, id) > (date, id), in the order of the listing.
          Path<LocalDateTime> createdDate = root.get("createdDate");
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
    return CursorPage.of(rows, page.limit(), ExpenseRepositoryAdapter::cursorOf)
        .map(ExpenseJpaEntity::toDomain);
  }

  @Override
  public BigDecimal sumByMonth(YearMonth month) {
    BigDecimal sum = jpaRepository.sumAmountByMonth(month.atDay(1));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private static String cursorOf(ExpenseJpaEntity expense) {
    return CursorToken.encode(
        // PostgreSQL keeps microseconds: a finer value would sort after the row it came from.
        expense.getCreatedDate().orElseThrow().truncatedTo(ChronoUnit.MICROS).toString(),
        Objects.requireNonNull(expense.getId()).toString());
  }

  /** The sort key of an expense, as the cursor carries it. */
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
