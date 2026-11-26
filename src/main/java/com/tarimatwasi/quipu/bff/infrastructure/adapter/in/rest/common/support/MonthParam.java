package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error.InvalidQueryParameterException;
import java.time.YearMonth;
import java.util.regex.Pattern;

/** The {@code month} (YYYY-MM) of the contract, as a query parameter or a body field. */
public final class MonthParam {

  /** For {@code @Pattern} on body fields; the query parameter goes through {@link #query}. */
  public static final String REGEX = "^\\d{4}-(0[1-9]|1[0-2])$";

  private static final Pattern MONTH = Pattern.compile(REGEX);

  private MonthParam() {}

  /** The month, or a 400 that names the {@code month} query parameter. */
  public static YearMonth query(String month) {
    if (!MONTH.matcher(month).matches()) {
      throw new InvalidQueryParameterException("month");
    }
    return YearMonth.parse(month);
  }
}
