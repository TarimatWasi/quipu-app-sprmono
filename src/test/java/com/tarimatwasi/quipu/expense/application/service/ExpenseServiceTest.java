package com.tarimatwasi.quipu.expense.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.expense.application.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.application.port.in.InvalidExpenseException;
import com.tarimatwasi.quipu.expense.application.port.in.InvalidExpenseMonthException;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseCommand;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseKind;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseView;
import com.tarimatwasi.quipu.expense.application.port.out.ExpenseRepositoryPort;
import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

  private static final Long ID = TestIds.next();
  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
  private static final BigDecimal AMOUNT = new BigDecimal("120.50");
  private static final Expense UTILITIES =
      new Expense(ID, ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, "Recibo", 0L);
  private static final ExpenseView UTILITIES_VIEW =
      new ExpenseView(ID, ExpenseKind.UTILITIES, AMOUNT, SEPTEMBER, "Recibo", 0L);

  @Mock ExpenseRepositoryPort repository;

  private ExpenseService service() {
    return new ExpenseService(repository);
  }

  private static ExpenseCommand command(BigDecimal amount, @Nullable String description) {
    return new ExpenseCommand(ExpenseKind.UTILITIES, amount, SEPTEMBER, description);
  }

  @Test
  void createsAndReturnsTheView() {
    when(repository.insert(ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, "Recibo"))
        .thenReturn(UTILITIES);

    assertThat(service().create(command(AMOUNT, " Recibo "))).isEqualTo(UTILITIES_VIEW);
  }

  @Test
  void aBlankDescriptionMeansNone() {
    when(repository.insert(ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, null))
        .thenReturn(new Expense(ID, ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, null, 0L));

    assertThat(service().create(command(AMOUNT, "   ")).description()).isNull();
  }

  @Test
  void anAmountThatIsNotPositiveHasTooManyDecimalsOrIsTooBigIsRejected() {
    for (var amount : new String[] {"0", "0.00", "-1", "10.001", "100000000.00"}) {
      assertThatThrownBy(() -> service().create(command(new BigDecimal(amount), null)))
          .as(amount)
          .isInstanceOf(InvalidExpenseException.class);
      assertThatThrownBy(() -> service().update(ID, 0L, command(new BigDecimal(amount), null)))
          .as(amount)
          .isInstanceOf(InvalidExpenseException.class);
    }

    verify(repository, never()).insert(any(), any(), any(), any());
    verify(repository, never()).update(any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void theLargestAmountAndTrailingZerosAreAccepted() {
    when(repository.insert(any(), any(), any(), any())).thenReturn(UTILITIES);

    service().create(command(new BigDecimal("99999999.99"), null));
    service().create(command(new BigDecimal("10.500"), null));

    verify(repository)
        .insert(ExpenseCategory.UTILITIES, new BigDecimal("99999999.99"), SEPTEMBER, null);
    verify(repository).insert(ExpenseCategory.UTILITIES, new BigDecimal("10.500"), SEPTEMBER, null);
  }

  @Test
  void updatesAnExistingExpenseAndFailsOnAnUnknownOne() {
    var other = TestIds.next();
    when(repository.update(ID, 0L, ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, null))
        .thenReturn(Optional.of(UTILITIES));
    when(repository.update(other, 0L, ExpenseCategory.UTILITIES, AMOUNT, SEPTEMBER, null))
        .thenReturn(Optional.empty());

    assertThat(service().update(ID, 0L, command(AMOUNT, null))).isEqualTo(UTILITIES_VIEW);
    assertThatThrownBy(() -> service().update(other, 0L, command(AMOUNT, null)))
        .isInstanceOf(ExpenseNotFoundException.class);
  }

  @Test
  void deletesAnExistingExpenseAndFailsOnAnUnknownOne() {
    var other = TestIds.next();
    when(repository.delete(ID)).thenReturn(true);
    when(repository.delete(other)).thenReturn(false);

    service().delete(ID);

    assertThatThrownBy(() -> service().delete(other)).isInstanceOf(ExpenseNotFoundException.class);
  }

  @Test
  void listsTheExpensesOfTheMonth() {
    PageQuery page = PageQuery.first();
    when(repository.findByMonth(SEPTEMBER, page))
        .thenReturn(new CursorPage<>(List.of(UTILITIES), "next"));

    when(repository.totalOfMonth(SEPTEMBER)).thenReturn(new ExpenseRepositoryPort.Total(AMOUNT, 3));

    var result = service().list(SEPTEMBER, page);

    assertThat(result.page().content()).containsExactly(UTILITIES_VIEW);
    assertThat(result.page().nextCursor()).isEqualTo("next");
    assertThat(result.totalCount()).isEqualTo(3);
  }

  /** Year 0 matches the pattern, but PostgreSQL keeps it as 1 BC and reads it back as year 1. */
  @Test
  void aMonthOutOfTheYearsOneTo9999IsRejectedOnCreateEditAndList() {
    for (var month : new YearMonth[] {YearMonth.of(0, 1), YearMonth.of(10_000, 1)}) {
      var command = new ExpenseCommand(ExpenseKind.UTILITIES, AMOUNT, month, null);
      assertThatThrownBy(() -> service().create(command))
          .as(month.toString())
          .isInstanceOf(InvalidExpenseMonthException.class);
      assertThatThrownBy(() -> service().update(ID, 0L, command))
          .as(month.toString())
          .isInstanceOf(InvalidExpenseMonthException.class);
      assertThatThrownBy(() -> service().list(month, PageQuery.first()))
          .as(month.toString())
          .isInstanceOf(InvalidExpenseMonthException.class);
    }

    verify(repository, never()).insert(any(), any(), any(), any());
    verify(repository, never()).update(any(), anyLong(), any(), any(), any(), any());
    verify(repository, never()).findByMonth(any(), any());
  }

  @Test
  void theEdgeYearsOneAnd9999AreAccepted() {
    for (var month : new YearMonth[] {YearMonth.of(1, 1), YearMonth.of(9999, 12)}) {
      var expense = new Expense(ID, ExpenseCategory.UTILITIES, AMOUNT, month, null, 0L);
      when(repository.insert(ExpenseCategory.UTILITIES, AMOUNT, month, null)).thenReturn(expense);
      when(repository.update(ID, 0L, ExpenseCategory.UTILITIES, AMOUNT, month, null))
          .thenReturn(Optional.of(expense));
      when(repository.findByMonth(month, PageQuery.first()))
          .thenReturn(new CursorPage<>(List.of(expense), null));
      var command = new ExpenseCommand(ExpenseKind.UTILITIES, AMOUNT, month, null);

      assertThat(service().create(command).month()).isEqualTo(month);
      assertThat(service().update(ID, 0L, command).month()).isEqualTo(month);
      when(repository.totalOfMonth(month)).thenReturn(new ExpenseRepositoryPort.Total(AMOUNT, 1));
      assertThat(service().list(month, PageQuery.first()).page().content()).hasSize(1);
    }
  }

  /** RF-18: the expenses of the month add up in the base, and a month of year 0000 is refused. */
  @Test
  void theTotalOfTheMonthIsTheSumTheBaseAnswers() {
    YearMonth october = YearMonth.of(2026, 10);
    when(repository.totalOfMonth(october))
        .thenReturn(new ExpenseRepositoryPort.Total(new BigDecimal("2000.00"), 2));

    assertThat(service().totalOf(october).amount()).isEqualByComparingTo("2000.00");
    assertThat(service().totalOf(october).count()).isEqualTo(2);
    assertThatThrownBy(() -> service().totalOf(YearMonth.of(0, 1)))
        .isInstanceOf(InvalidExpenseMonthException.class);
  }
}
