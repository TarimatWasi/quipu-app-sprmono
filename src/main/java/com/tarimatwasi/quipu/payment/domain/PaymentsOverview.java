package com.tarimatwasi.quipu.payment.domain;

import java.util.Collection;

/**
 * HU-A04, CU-04: how many charges of today stand in each state (RN-06). Each one is the period of
 * the current month of an active contract or the stay in course of a temporary guest; the states
 * are the ones the account status shows.
 *
 * @param upToDate PAID
 * @param underReview UNDER_REVIEW
 * @param pending UNPAID, not yet overdue
 * @param overdue OVERDUE
 */
public record PaymentsOverview(int upToDate, int underReview, int pending, int overdue) {

  /** One count per state; no states, all zeros. */
  public static PaymentsOverview of(Collection<PeriodState> states) {
    int upToDate = 0;
    int underReview = 0;
    int pending = 0;
    int overdue = 0;
    for (PeriodState state : states) {
      switch (state) {
        case PAID -> upToDate++;
        case UNDER_REVIEW -> underReview++;
        case UNPAID -> pending++;
        case OVERDUE -> overdue++;
      }
    }
    return new PaymentsOverview(upToDate, underReview, pending, overdue);
  }
}
