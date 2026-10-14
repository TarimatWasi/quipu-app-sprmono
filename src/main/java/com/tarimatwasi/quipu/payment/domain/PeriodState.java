package com.tarimatwasi.quipu.payment.domain;

/** The state of one period of a contract, or of a stay, derived on the day it is asked (RN-06). */
public enum PeriodState {
  /** A payment of the period is approved. */
  PAID,
  /** A voucher is waiting for the review and none is approved. */
  UNDER_REVIEW,
  /** Nothing alive, and the due date plus the grace days have not passed. */
  UNPAID,
  /** Nothing alive, and the due date plus the grace days have passed (RN-10). */
  OVERDUE
}
