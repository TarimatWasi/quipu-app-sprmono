package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.response.PagedResponse;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.PageParams;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseCommand;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseKind;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpensePage;
import com.tarimatwasi.quipu.expense.application.port.in.ManageExpensesUseCase.ExpenseView;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private final IdMasker ids;

  public ExpensesBffController(ManageExpensesUseCase expenses, IdMasker ids) {
    this.expenses = expenses;
    this.ids = ids;
  }

  /** The use case checks the amount: greater than 0, at most two decimals and the column limit. */
  public record ExpenseRequest(
      @NotNull ExpenseKind category,
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = MONTH) String month,
      @Nullable @Pattern(regexp = NO_NUL) String description) {}

  /** ExpenseUpdateInput: the whole expense again, with the {@code version} loaded (TAR-240). */
  public record ExpenseUpdateRequest(
      @NotNull ExpenseKind category,
      @NotNull BigDecimal amount,
      @NotNull @Pattern(regexp = MONTH) String month,
      @Nullable @Pattern(regexp = NO_NUL) String description,
      @NotNull @Min(0) Long version) {}

  /** The description is left out when there is none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ExpenseResponse(
      UUID id,
      long version,
      String category,
      BigDecimal amount,
      String month,
      @Nullable String description) {
    static ExpenseResponse of(ExpenseView expense, IdMasker ids) {
      return new ExpenseResponse(
          ids.mask(IdKind.EXPENSE, expense.id()),
          expense.version(),
          expense.category().name(),
          expense.amount(),
          expense.month().toString(),
          expense.description());
    }
  }

  /** PagedExpenses: one page and how many the month has (A6 «Mostrando X de Y»). */
  public record PagedExpensesResponse(
      List<ExpenseResponse> content, @Nullable String nextCursor, long totalCount) {}

  @GetMapping("/bff/admin/expenses")
  public PagedExpensesResponse list(
      @RequestParam("month") String month,
      @RequestParam(name = "limit", defaultValue = "20") int limit,
      @RequestParam(name = "after", required = false) @Nullable String after) {
    if (!MONTH_PATTERN.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    ExpensePage found = expenses.list(YearMonth.parse(month), PageParams.query(after, limit));
    PagedResponse<ExpenseResponse> page =
        PagedResponse.of(found.page(), expense -> ExpenseResponse.of(expense, ids));
    return new PagedExpensesResponse(page.content(), page.nextCursor(), found.totalCount());
  }

  @PostMapping("/bff/admin/expenses")
  public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request) {
    ExpenseResponse created = ExpenseResponse.of(expenses.create(command(request)), ids);
    return ResponseEntity.created(URI.create("/bff/admin/expenses/" + created.id())).body(created);
  }

  @PatchMapping("/bff/admin/expenses/{id}")
  public ExpenseResponse update(
      @PathVariable UUID id, @Valid @RequestBody ExpenseUpdateRequest request) {
    return ExpenseResponse.of(
        expenses.update(
            ids.unmask(IdKind.EXPENSE, id),
            request.version(),
            new ExpenseCommand(
                request.category(),
                request.amount(),
                YearMonth.parse(request.month()),
                request.description())),
        ids);
  }

  @DeleteMapping("/bff/admin/expenses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    expenses.delete(ids.unmask(IdKind.EXPENSE, id));
  }

  private static ExpenseCommand command(ExpenseRequest request) {
    return new ExpenseCommand(
        request.category(),
        request.amount(),
        YearMonth.parse(request.month()),
        request.description());
  }
}
