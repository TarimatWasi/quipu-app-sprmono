package com.tarimatwasi.quipu.payment.adapter.out.stay;

import com.tarimatwasi.quipu.guest.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
import org.springframework.stereotype.Component;

/** The payment module asks the guest module, through its exposed port, about a stay. */
@Component
public class StayPaymentAdapter implements PaymentStayPort {

  private final ManageStaysUseCase stays;

  StayPaymentAdapter(ManageStaysUseCase stays) {
    this.stays = stays;
  }

  @Override
  public StayInfo stayOf(Long guestId, Long stayId) {
    return new StayInfo(stays.get(guestId, stayId).id());
  }
}
