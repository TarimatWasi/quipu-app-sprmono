package com.tarimatwasi.quipu.fine.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RF-19: the amount of a fine and the month it counts in. */
class FineTest {

  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

  @ParameterizedTest
  @ValueSource(strings = {"0.01", "1", "10.5", "10.50", "10.500", "99999999.99"})
  void anAmountGreaterThanZeroWithAtMostTwoDecimalsThatFitsTheColumnIsValid(String amount) {
    assertThat(Fine.isValidAmount(new BigDecimal(amount))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"0", "0.00", "-0.01", "-10", "10.001", "0.001", "100000000", "99999999.991"})
  void anAmountThatIsNotPositiveHasMoreDecimalsOrDoesNotFitIsNotValid(String amount) {
    assertThat(Fine.isValidAmount(new BigDecimal(amount))).isFalse();
  }

  @Test
  void aFineWithAnInvalidAmountCannotBeBuilt() {
    assertThatThrownBy(() -> new Fine(1L, 2L, BigDecimal.ZERO, SEPTEMBER, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theMonthsGoFromYearOneToYear9999() {
    assertThat(Fine.isValidMonth(YearMonth.of(1, 1))).isTrue();
    assertThat(Fine.isValidMonth(YearMonth.of(9999, 12))).isTrue();
    assertThat(Fine.isValidMonth(YearMonth.of(0, 12))).isFalse();
    assertThat(Fine.isValidMonth(YearMonth.of(10000, 1))).isFalse();
    assertThatThrownBy(() -> new Fine(1L, 2L, BigDecimal.ONE, YearMonth.of(0, 1), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFineKeepsItsMonthAndStartsOnItsFirstDay() {
    var fine = new Fine(1L, 2L, new BigDecimal("15.00"), SEPTEMBER, "Ruido");

    assertThat(fine.month()).isEqualTo(SEPTEMBER);
    assertThat(fine.firstDay()).isEqualTo(LocalDate.of(2026, 9, 1));
  }

  @Test
  void theMonthIsReadFromItsFirstDay() {
    assertThat(Fine.monthOf(LocalDate.of(2026, 2, 1))).isEqualTo(YearMonth.of(2026, 2));
  }

  @Test
  void aDayThatIsNotTheFirstIsNotAMonth() {
    assertThatThrownBy(() -> Fine.monthOf(LocalDate.of(2026, 2, 2)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
