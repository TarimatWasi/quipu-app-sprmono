package com.tarimatwasi.quipu.expense.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.expense.domain.Expense;
import com.tarimatwasi.quipu.expense.domain.ExpenseCategory;
import com.tarimatwasi.quipu.expense.port.in.ExpenseNotFoundException;
import com.tarimatwasi.quipu.expense.port.in.InvalidExpenseException;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseCommand;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseKind;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseView;
import com.tarimatwasi.quipu.expense.port.out.ExpenseRepositoryPort;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

  private static final UUID ID = UUID.randomUUID();
  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
  private static final BigDecimal AMOUNT = new BigDecimal("120.50");
  private static final Expense WATER =
      new Expense(ID, ExpenseCategory.WATER, AMOUNT, SEPTEMBER, "Recibo");
  private static final ExpenseView WATER_VIEW =
      new ExpenseView(ID, ExpenseKind.WATER, AMOUNT, SEPTEMBER, "Recibo");

  @Mock ExpenseRepositoryPort repository;

  private ExpenseService service() {
    return new ExpenseService(repository);
  }

  private static ExpenseCommand command(BigDecimal amount, @Nullable String description) {
    return new ExpenseCommand(ExpenseKind.WATER, amount, SEPTEMBER, description);
  }

  @Test
  void createsAndReturnsTheView() {
    when(repository.insert(ExpenseCategory.WATER, AMOUNT, SEPTEMBER, "Recibo")).thenReturn(WATER);

    assertThat(service().create(command(AMOUNT, " Recibo "))).isEqualTo(WATER_VIEW);
  }

  @Test
  void aBlankDescriptionMeansNone() {
    when(repository.insert(ExpenseCategory.WATER, AMOUNT, SEPTEMBER, null))
        .thenReturn(new Expense(ID, ExpenseCategory.WATER, AMOUNT, SEPTEMBER, null));

    assertThat(service().create(command(AMOUNT, "   ")).description()).isNull();
  }

  @Test
  void anAmountThatIsNotPositiveHasTooManyDecimalsOrIsTooBigIsRejected() {
    for (var amount : new String[] {"0", "0.00", "-1", "10.001", "100000000.00"}) {
      assertThatThrownBy(() -> service().create(command(new BigDecimal(amount), null)))
          .as(amount)
          .isInstanceOf(InvalidExpenseException.class);
      assertThatThrownBy(() -> service().update(ID, command(new BigDecimal(amount), null)))
          .as(amount)
          .isInstanceOf(InvalidExpenseException.class);
    }

    verify(repository, never()).insert(any(), any(), any(), any());
    verify(repository, never()).update(any(), any(), any(), any(), any());
  }

  @Test
  void theLargestAmountAndTrailingZerosAreAccepted() {
    when(repository.insert(any(), any(), any(), any())).thenReturn(WATER);

    service().create(command(new BigDecimal("99999999.99"), null));
    service().create(command(new BigDecimal("10.500"), null));

    verify(repository)
        .insert(ExpenseCategory.WATER, new BigDecimal("99999999.99"), SEPTEMBER, null);
    verify(repository).insert(ExpenseCategory.WATER, new BigDecimal("10.500"), SEPTEMBER, null);
  }

  @Test
  void updatesAnExistingExpenseAndFailsOnAnUnknownOne() {
    var other = UUID.randomUUID();
    when(repository.update(ID, ExpenseCategory.WATER, AMOUNT, SEPTEMBER, null))
        .thenReturn(Optional.of(WATER));
    when(repository.update(other, ExpenseCategory.WATER, AMOUNT, SEPTEMBER, null))
        .thenReturn(Optional.empty());

    assertThat(service().update(ID, command(AMOUNT, null))).isEqualTo(WATER_VIEW);
    assertThatThrownBy(() -> service().update(other, command(AMOUNT, null)))
        .isInstanceOf(ExpenseNotFoundException.class);
  }

  @Test
  void deletesAnExistingExpenseAndFailsOnAnUnknownOne() {
    var other = UUID.randomUUID();
    when(repository.delete(ID)).thenReturn(true);
    when(repository.delete(other)).thenReturn(false);

    service().delete(ID);

    assertThatThrownBy(() -> service().delete(other)).isInstanceOf(ExpenseNotFoundException.class);
  }

  @Test
  void listsTheExpensesOfTheMonth() {
    when(repository.findByMonth(SEPTEMBER)).thenReturn(List.of(WATER));

    assertThat(service().list(SEPTEMBER)).containsExactly(WATER_VIEW);
  }
}
