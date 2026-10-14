package com.tarimatwasi.quipu.payment.adapter.out.guest;

import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The payment module asks the guest module, through its exposed port, about its guests. */
@Component
public class GuestPaymentAdapter implements PaymentGuestPort {

  private final ManageGuestsUseCase guests;

  GuestPaymentAdapter(ManageGuestsUseCase guests) {
    this.guests = guests;
  }

  @Override
  public GuestProfile profileOf(Long guestId) {
    GuestSummaryView guest = guests.get(guestId).summary();
    return new GuestProfile(
        guest.id(),
        guest.name(),
        guest.documentNumber(),
        guest.type() == GuestKind.CONTRACT,
        guest.status() == GuestState.ACTIVE);
  }

  // ponytail: one call to the guest module per distinct guest of the page (at most 100); a batch
  // read in the guest module if a queue page ever needs more.
  @Override
  public Map<Long, GuestProfile> profilesOf(Collection<Long> guestIds) {
    Map<Long, GuestProfile> profiles = new HashMap<>();
    for (Long id : guestIds) {
      profiles.put(id, profileOf(id));
    }
    return profiles;
  }
}
