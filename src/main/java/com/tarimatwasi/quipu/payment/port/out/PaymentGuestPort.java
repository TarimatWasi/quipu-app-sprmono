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
   * @param inactive deactivated by the administrator (RN-12); never true with {@code active}
   */
  record GuestProfile(
      Long id,
      @Nullable String name,
      String documentNumber,
      boolean contractType,
      boolean active,
      boolean inactive) {}

  /** Where the guest of a session is in its lifecycle (RN-36), as the payments read it. */
  enum Standing {
    /** It has not finished the onboarding yet (RN-33), or has not even activated its access. */
    ONBOARDING,
    ACTIVE,
    INACTIVE
  }

  /**
   * The guest of a session, read with its own rights.
   *
   * @param contractType a guest with a contract; otherwise a temporary one
   */
  record OwnGuest(Long id, boolean contractType, Standing standing) {}

  /**
   * The guest of the session.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if there is none
   */
  OwnGuest ownGuest(Long guestId);

  /**
   * One guest.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if there is none
   */
  GuestProfile profileOf(Long guestId);

  /**
   * Locks the guest until the transaction ends (order: environment, guest, contract, payment), so a
   * deactivation that races a direct payment is seen or waits (TAR-38). Needs a transaction.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException if there is none
   */
  void lock(Long guestId);

  /** The guests with those ids by id. */
  Map<Long, GuestProfile> profilesOf(Collection<Long> guestIds);
}
