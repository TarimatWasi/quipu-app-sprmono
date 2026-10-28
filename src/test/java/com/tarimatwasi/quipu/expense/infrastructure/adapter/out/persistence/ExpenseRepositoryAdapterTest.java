package com.tarimatwasi.quipu.expense.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.NoMaskSessionException;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.AdminSessions;
import com.tarimatwasi.quipu.support.MaskedCursors;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** RN-24 (TAR-171): the version guards the edits, never the deletion of an expense. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ExpenseRepositoryAdapterTest {

  @Autowired ExpenseRepositoryAdapter adapter;
  @Autowired ExpenseJpaRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;
  @Autowired IdMasker masker;
  @Autowired ApplicationContext context;

  private Long admin;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    admin = AdminSessions.insertAdmin(jdbc);
    AdminSessions.signIn(context, admin);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private Expense inserted() {
    return adapter.insert(
        ExpenseCategory.UTILITIES, new BigDecimal("10.00"), YearMonth.of(2026, 9), null);
  }

  @Test
  void delete_ofAnExpenseEditedAfterItWasRead_stillDeletesIt() {
    Expense expense = inserted();

    boolean deleted =
        Boolean.TRUE.equals(
            transaction.execute(
                status -> {
                  // Read first (the persistence context keeps this version) ...
                  repository.findById(expense.id());
                  // ... and someone else edits it before the delete.
                  jdbc.update(
                      "UPDATE expenses SET version = version + 1 WHERE id = ?", expense.id());
                  return adapter.delete(expense.id());
                }));

    assertThat(deleted).isTrue();
    assertThat(repository.findById(expense.id())).isEmpty();
  }

  @Test
  void delete_ofAMissingExpense_returnsFalse() {
    // As in production, where the application service opens the transaction (BE-SPR-APP-02).
    Boolean deleted = transaction.execute(status -> adapter.delete(987_654L));

    assertThat(deleted).isFalse();
  }

  private void insertInMonth(int amount, YearMonth month) {
    adapter.insert(ExpenseCategory.TAXES_FEES, new BigDecimal(amount), month, null);
  }

  /** BE-SPR-WEB-08 (TAR-176): read by last index, oldest first, with no offset. */
  @Test
  void findByMonth_walksTheMonthOldestFirstWithoutRepeatingOrSkipping() {
    for (int amount = 1; amount <= 5; amount++) {
      insertInMonth(amount, YearMonth.of(2026, 9));
    }
    insertInMonth(99, YearMonth.of(2026, 10));

    List<Integer> amounts = new ArrayList<>();
    PageQuery query = new PageQuery(null, 2);
    int pages = 0;
    do {
      CursorPage<Expense> page = adapter.findByMonth(YearMonth.of(2026, 9), query);
      page.content().forEach(expense -> amounts.add(expense.amount().intValue()));
      query = new PageQuery(page.nextCursor(), 2);
      pages++;
    } while (query.after() != null);

    assertThat(amounts).containsExactly(1, 2, 3, 4, 5);
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void findByMonth_ofAnEmptyMonth_isAnEmptyLastPage() {
    CursorPage<Expense> page = adapter.findByMonth(YearMonth.of(2026, 1), PageQuery.first());

    assertThat(page.content()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void findByMonth_withAnInvalidCursor_isRefused() {
    YearMonth month = YearMonth.of(2026, 9);
    for (String cursor :
        new String[] {
          "garbage!",
          CursorToken.encode("not-a-date", "1"),
          CursorToken.encode("2026-09-01T10:00:00", "not-a-number"),
          // Valid for Java, outside the range of a PostgreSQL timestamp: it would be a 500.
          CursorToken.encode("+999999999-12-31T23:59:59", "1"),
          CursorToken.encode("2026-09-01T10:00:00")
        }) {
      assertThatThrownBy(() -> adapter.findByMonth(month, new PageQuery(cursor, 5)))
          .isInstanceOf(InvalidCursorException.class);
    }
  }

  /** Same creation instant: the id breaks the tie, and no row is repeated or skipped. */
  @Test
  void findByMonth_withTheSameCreationInstant_ordersAndPagesById() {
    for (int amount = 1; amount <= 3; amount++) {
      jdbc.update(
          "INSERT INTO expenses (id, type, amount, month, created_date, last_modified_date)"
              + " VALUES (?, 'TAXES_FEES', ?, DATE '2026-09-01', TIMESTAMPTZ '2026-09-02 10:00:00+00',"
              + " TIMESTAMPTZ '2026-09-02 10:00:00+00')",
          10_000L + amount,
          amount);
    }

    List<Integer> amounts = new ArrayList<>();
    PageQuery query = new PageQuery(null, 1);
    do {
      CursorPage<Expense> page = adapter.findByMonth(YearMonth.of(2026, 9), query);
      page.content().forEach(expense -> amounts.add(expense.amount().intValue()));
      query = new PageQuery(page.nextCursor(), 1);
    } while (query.after() != null);

    assertThat(amounts).containsExactly(1, 2, 3);
  }

  /** An expense with a large id of the test, so that a cursor that carried it would show it. */
  private Long jdbcExpense(int amount, String month, String createdDate) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO expenses (id, type, amount, month, created_date, last_modified_date)"
            + " VALUES (?, 'TAXES_FEES', ?, ?::date, ?::timestamptz, ?::timestamptz)",
        id,
        amount,
        month,
        createdDate,
        createdDate);
    return id;
  }

  private List<Integer> amounts(CursorPage<Expense> page) {
    return page.content().stream().map(expense -> expense.amount().intValue()).toList();
  }

  /**
   * TAR-227: the cursor hides the internal id and the date, and serves only its own session and its
   * own month.
   */
  @Test
  void findByMonth_cursorIsOpaqueAndValidOnlyInItsSessionAndMonth() {
    YearMonth september = YearMonth.of(2026, 9);
    Long first = jdbcExpense(1, "2026-09-01", "2026-09-02T10:00:00Z");
    Long second = jdbcExpense(2, "2026-09-01", "2026-09-03T10:00:00Z");
    Long third = jdbcExpense(3, "2026-09-01", "2026-09-04T10:00:00Z");
    jdbcExpense(4, "2026-10-01", "2026-09-05T10:00:00Z");

    CursorPage<Expense> page = adapter.findByMonth(september, new PageQuery(null, 2));
    assertThat(amounts(page)).containsExactly(1, 2);
    String cursor = Objects.requireNonNull(page.nextCursor());

    String key =
        MaskedCursors.assertOpaque(
            cursor, masker, IdKind.EXPENSE, second, List.of(first, second, third), "2026");
    CursorPage<Expense> next = adapter.findByMonth(september, new PageQuery(cursor, 2));
    assertThat(amounts(next)).containsExactly(3);
    assertThat(next.nextCursor()).isNull();

    for (String bad :
        MaskedCursors.forgeries(key, masker, IdKind.STAY, second, "2026-09-03T10:00:00")) {
      assertThatThrownBy(() -> adapter.findByMonth(september, new PageQuery(bad, 2)))
          .as(bad)
          .isInstanceOf(InvalidCursorException.class);
    }
    // the position of an expense of September is not one in October
    assertThatThrownBy(() -> adapter.findByMonth(YearMonth.of(2026, 10), new PageQuery(cursor, 2)))
        .isInstanceOf(InvalidCursorException.class);

    // another session of the same administrator
    AdminSessions.signIn(context, admin);
    assertThatThrownBy(() -> adapter.findByMonth(september, new PageQuery(cursor, 2)))
        .isInstanceOf(InvalidCursorException.class);
    String again =
        Objects.requireNonNull(adapter.findByMonth(september, new PageQuery(null, 2)).nextCursor());
    assertThat(again).isNotEqualTo(cursor);
    assertThat(amounts(adapter.findByMonth(september, new PageQuery(again, 2)))).containsExactly(3);

    // the expense of the cursor is no longer there
    jdbc.update("DELETE FROM expenses WHERE id = ?", second);
    assertThatThrownBy(() -> adapter.findByMonth(september, new PageQuery(again, 2)))
        .isInstanceOf(InvalidCursorException.class);

    // with no session at all it fails closed
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> adapter.findByMonth(september, new PageQuery(null, 1)))
        .isInstanceOf(NoMaskSessionException.class);
  }
}
