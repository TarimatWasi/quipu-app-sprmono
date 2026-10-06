package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseCommand;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseKind;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase.ExpenseView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** RF-07. Only ADMIN reaches /bff/admin/** (RN-08); SecurityConfig enforces it. */
@RestController
public class ExpensesBffController {

  // PostgreSQL cannot store the NUL character in a TEXT column
  private static final String NO_NUL = "(?s)[^\\x00]*";

  private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

  private static final java.util.regex.Pattern MONTH_PATTERN =
      java.util.regex.Pattern.compile(MONTH);

  private final ManageExpensesUseCase expenses;

  public ExpensesBffController(ManageExpensesUseCase expenses) {
    this.expenses = expenses;
  }

  /** The use case checks the amount: greater than 0, at most two decimals and the column limit. */
  public record ExpenseRequest(
      @NotNull ExpenseKind category,
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = MONTH) String month,
      @Nullable @Pattern(regexp = NO_NUL) String description) {}

  /** The description is left out when there is none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ExpenseResponse(
      UUID id, String category, BigDecimal amount, String month, @Nullable String description) {
    static ExpenseResponse of(ExpenseView expense) {
      return new ExpenseResponse(
          expense.id(),
          expense.category().name(),
          expense.amount(),
          expense.month().toString(),
          expense.description());
    }
  }

  @GetMapping("/bff/admin/expenses")
  public List<ExpenseResponse> list(@RequestParam("month") String month) {
    if (!MONTH_PATTERN.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    return expenses.list(YearMonth.parse(month)).stream().map(ExpenseResponse::of).toList();
  }

  @PostMapping("/bff/admin/expenses")
  @ResponseStatus(HttpStatus.CREATED)
  public ExpenseResponse create(@Valid @RequestBody ExpenseRequest request) {
    return ExpenseResponse.of(expenses.create(command(request)));
  }

  @PatchMapping("/bff/admin/expenses/{id}")
  public ExpenseResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
    return ExpenseResponse.of(expenses.update(id, command(request)));
  }

  @DeleteMapping("/bff/admin/expenses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    expenses.delete(id);
  }

  private static ExpenseCommand command(ExpenseRequest request) {
    return new ExpenseCommand(
        request.category(),
        request.amount(),
        YearMonth.parse(request.month()),
        request.description());
  }
}
