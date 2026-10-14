package com.tarimatwasi.quipu.payment.port.out;

public interface PaymentStayPort {

  /** A stay of a temporary guest. */
  record StayInfo(Long id) {}

  /**
   * The stay, only if it belongs to that guest.
   *
   * @throws com.tarimatwasi.quipu.guest.port.in.StayNotFoundException if the guest has no such stay
   */
  StayInfo stayOf(Long guestId, Long stayId);
}
