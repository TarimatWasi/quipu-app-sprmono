package com.tarimatwasi.quipu.payment.adapter.out.fine;

import com.tarimatwasi.quipu.fine.port.in.ManageFinesUseCase;
import com.tarimatwasi.quipu.payment.port.out.PaymentFinePort;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/** The payment module asks the fine module, through its exposed port, about a month. */
@Component
public class FinePaymentAdapter implements PaymentFinePort {

  private final ManageFinesUseCase fines;

  FinePaymentAdapter(ManageFinesUseCase fines) {
    this.fines = fines;
  }

  @Override
  public BigDecimal totalOf(YearMonth month) {
    return fines.totalOf(month);
  }
}
