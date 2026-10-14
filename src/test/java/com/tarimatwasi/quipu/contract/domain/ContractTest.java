package com.tarimatwasi.quipu.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.contract.domain.Contract.Violation;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** RN-07: the state of a contract comes from its dates, with no stored EXPIRED. */
class ContractTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");
  private static final BigDecimal AMOUNT = new BigDecimal("800.00");

  private static Contract contract(String start, @Nullable String end, boolean terminated) {
    return new Contract(
        1L,
        2L,
        3L,
        LocalDate.parse(start),
        end == null ? null : LocalDate.parse(end),
        AMOUNT,
        5,
        3,
        "contracts/a.pdf",
        "a.pdf",
        100,
        terminated);
  }

  @Test
  void anOpenEndedContractIsActive() {
    assertThat(contract("2026-01-01", null, false).statusAt(TODAY))
        .isEqualTo(ContractStatus.ACTIVE);
  }

  @Test
  void theEndDateIsInclusive() {
    assertThat(contract("2026-01-01", "2026-10-14", false).statusAt(TODAY))
        .isEqualTo(ContractStatus.ACTIVE);
    assertThat(contract("2026-01-01", "2026-10-13", false).statusAt(TODAY))
        .isEqualTo(ContractStatus.EXPIRED);
  }

  @Test
  void aTerminatedContractStaysTerminatedWhateverItsDates() {
    assertThat(contract("2026-01-01", null, true).statusAt(TODAY))
        .isEqualTo(ContractStatus.TERMINATED);
    assertThat(contract("2025-01-01", "2025-02-01", true).statusAt(TODAY))
        .isEqualTo(ContractStatus.TERMINATED);
  }

  @Test
  void onlyAnActiveContractOccupiesAndOneThatStartsInTheFutureAlsoDoes() {
    assertThat(contract("2026-01-01", null, false).occupies(TODAY)).isTrue();
    assertThat(contract("2026-12-01", "2027-12-01", false).occupies(TODAY)).isTrue();
    assertThat(contract("2025-01-01", "2026-10-13", false).occupies(TODAY)).isFalse();
    assertThat(contract("2026-01-01", null, true).occupies(TODAY)).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-14, true",
    "2026-11-13, true",
    "2026-11-14, false",
    "2026-11-15, false",
    "2027-10-14, false"
  })
  void expiresSoonWhenThirtyDaysOrFewerRemain(String end, boolean expected) {
    assertThat(contract("2026-01-01", end, false).expiringSoon(TODAY)).isEqualTo(expected);
  }

  @Test
  void anOpenEndedExpiredOrTerminatedContractDoesNotExpireSoon() {
    assertThat(contract("2026-01-01", null, false).expiringSoon(TODAY)).isFalse();
    assertThat(contract("2025-01-01", "2026-10-13", false).expiringSoon(TODAY)).isFalse();
    assertThat(contract("2026-01-01", "2026-10-20", true).expiringSoon(TODAY)).isFalse();
  }

  @Test
  void terminatingGivesATerminatedCopy() {
    Contract active = contract("2026-01-01", null, false);

    Contract finished = active.terminate();

    assertThat(finished.terminated()).isTrue();
    assertThat(finished.id()).isEqualTo(active.id());
    assertThat(active.terminated()).isFalse();
  }

  private static @Nullable Violation violation(
      String start, @Nullable String end, String amount, int paymentDay, int graceDays) {
    return Contract.violationOf(
        LocalDate.parse(start),
        end == null ? null : LocalDate.parse(end),
        new BigDecimal(amount),
        paymentDay,
        graceDays);
  }

  @Test
  void validTermsHaveNoViolation() {
    assertThat(violation("2026-01-01", null, "800", 1, 0)).isNull();
    assertThat(violation("2026-01-01", "2026-01-02", "99999999.99", 31, 31)).isNull();
    assertThat(violation("1900-01-01", "9999-12-31", "0.01", 15, 5)).isNull();
  }

  @Test
  void theEndDateMustBeAfterTheStartDate() {
    assertThat(violation("2026-01-01", "2026-01-01", "800", 5, 3)).isEqualTo(Violation.DATES_ORDER);
    assertThat(violation("2026-01-02", "2026-01-01", "800", 5, 3)).isEqualTo(Violation.DATES_ORDER);
    assertThat(Violation.DATES_ORDER.field()).isEqualTo("endDate");
  }

  @Test
  void theYearsAreBetween1900And9999() {
    assertThat(violation("1899-12-31", null, "800", 5, 3)).isEqualTo(Violation.START_DATE);
    assertThat(violation("+10000-01-01", null, "800", 5, 3)).isEqualTo(Violation.START_DATE);
    assertThat(violation("2026-01-01", "+10000-01-01", "800", 5, 3)).isEqualTo(Violation.END_DATE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.001", "800.123", "100000000", "99999999.991"})
  void theAmountIsPositiveWithTwoDecimalsAndFitsTheColumn(String amount) {
    assertThat(violation("2026-01-01", null, amount, 5, 3)).isEqualTo(Violation.MONTHLY_AMOUNT);
  }

  @Test
  void trailingZerosBeyondTwoDecimalsAreNotAThirdDecimal() {
    assertThat(violation("2026-01-01", null, "800.100", 5, 3)).isNull();
  }

  @Test
  void thePaymentDayIsBetween1And31AndTheGraceBetween0And31() {
    assertThat(violation("2026-01-01", null, "800", 0, 3)).isEqualTo(Violation.PAYMENT_DAY);
    assertThat(violation("2026-01-01", null, "800", 32, 3)).isEqualTo(Violation.PAYMENT_DAY);
    assertThat(violation("2026-01-01", null, "800", 5, -1)).isEqualTo(Violation.GRACE_DAYS);
    assertThat(violation("2026-01-01", null, "800", 5, 32)).isEqualTo(Violation.GRACE_DAYS);
    assertThat(Violation.PAYMENT_DAY.field()).isEqualTo("paymentDay");
    assertThat(Violation.GRACE_DAYS.field()).isEqualTo("graceDays");
    assertThat(Violation.MONTHLY_AMOUNT.field()).isEqualTo("monthlyAmount");
    assertThat(Violation.START_DATE.field()).isEqualTo("startDate");
  }
}
