package com.tarimatwasi.quipu.payment.domain;

/**
 * Where a payment is in its review (RN-04, RN-05). A rejected payment is corrected and sent again,
 * so it still holds its period or stay (TAR-239).
 */
public enum PaymentStatus {
  PENDING,
  APPROVED,
  REJECTED,
  /**
   * A rejection from before TAR-239 that another payment of the same period or stay replaced: it is
   * history, shown as REJECTED, and holds nothing.
   */
  SUPERSEDED;

  /** The status the contract shows: SUPERSEDED reads as the rejection it was. */
  public PaymentStatus shown() {
    return this == SUPERSEDED ? REJECTED : this;
  }
}
