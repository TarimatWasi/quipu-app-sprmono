package com.tarimatwasi.quipu.payment.domain;

/** Where a voucher is in its review (RN-04, RN-05). A payment is resolved once. */
public enum PaymentStatus {
  PENDING,
  APPROVED,
  REJECTED
}
