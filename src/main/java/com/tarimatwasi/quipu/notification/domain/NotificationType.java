package com.tarimatwasi.quipu.notification.domain;

/** What happened, and so who hears about it and how the text reads (contract v0.27.0). */
public enum NotificationType {
  PAYMENT_PROOF_SUBMITTED(Audience.ADMINISTRATORS),
  ONBOARDING_COMPLETED(Audience.ADMINISTRATORS),
  GUEST_DOCUMENT_UPLOADED(Audience.ADMINISTRATORS),
  CONTRACT_EXPIRING(Audience.ADMINISTRATORS),
  PAYMENT_OVERDUE(Audience.ADMINISTRATORS),
  STAY_ENDING(Audience.ADMINISTRATORS),
  ACCESS_EXPIRED(Audience.ADMINISTRATORS),
  PAYMENT_APPROVED(Audience.GUEST),
  PAYMENT_REJECTED(Audience.GUEST),
  REFUND_CREATED(Audience.GUEST),
  FINE_APPLIED(Audience.GUEST),
  CONTRACT_SIGNED(Audience.GUEST),
  CONTRACT_TERMINATED(Audience.GUEST),
  PAYMENT_DUE(Audience.GUEST);

  private final Audience audience;

  NotificationType(Audience audience) {
    this.audience = audience;
  }

  /** Who reads notifications of this type. */
  public Audience audience() {
    return audience;
  }
}
