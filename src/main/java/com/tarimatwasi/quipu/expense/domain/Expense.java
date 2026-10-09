package com.tarimatwasi.quipu.expense.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/** An operating expense of one month; the amount is in soles, greater than 0 (RF-07). */
public record Expense(
    Long id,
    ExpenseCategory category,
    BigDecimal amount,
    YearMonth month,
    @Nullable String description) {}
