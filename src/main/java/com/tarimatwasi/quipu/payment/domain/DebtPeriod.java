package com.tarimatwasi.quipu.payment.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/** A period whose due date has passed without an approved payment (RN-25). */
public record DebtPeriod(YearMonth period, LocalDate dueDate) {}
