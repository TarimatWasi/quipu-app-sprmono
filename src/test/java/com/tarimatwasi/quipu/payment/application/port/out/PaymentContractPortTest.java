package com.tarimatwasi.quipu.payment.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PaymentContractPortTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 1);
  private static final LocalDate END = LocalDate.of(2026, 12, 31);

  /** It ended on the earlier of its end date and the day it was finished. */
  @Test
  void aContractEndsOnTheEarlierOfItsEndAndItsFinish() {
    LocalDate early = LocalDate.of(2026, 6, 15);
    LocalDate late = LocalDate.of(2027, 2, 1);

    assertThat(contract(END, null).endedOn()).isEqualTo(END);
    assertThat(contract(null, null).endedOn()).isNull();
    assertThat(contract(END, early).endedOn()).isEqualTo(early);
    assertThat(contract(END, late).endedOn()).isEqualTo(END);
    assertThat(contract(null, early).endedOn()).isEqualTo(early);
  }

  private static ContractInfo contract(@Nullable LocalDate end, @Nullable LocalDate finished) {
    return new ContractInfo(
        1L, 2L, new ContractTerms(START, end, null, 5, 3), new BigDecimal("600"), finished);
  }
}
