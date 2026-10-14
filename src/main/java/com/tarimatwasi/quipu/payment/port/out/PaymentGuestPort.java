package com.tarimatwasi.quipu.payment.port.out;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface PaymentGuestPort {

  /**
   * What the payments need to know of a guest.
   *
   * @param name {@code null} until the guest gives it in the onboarding
   * @param contractType a guest with a contract; otherwise a temporary one
   */
  record GuestProfile(
      Long id,
      @Nullable String name,
      String documentNumber,
      boolean contractType,
      boolean active) {}

  /**
   * One guest.
   *
   * @throws com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException if there is none
   */
  GuestProfile profileOf(Long guestId);

  /** The guests with those ids by id. */
  Map<Long, GuestProfile> profilesOf(Collection<Long> guestIds);
}
