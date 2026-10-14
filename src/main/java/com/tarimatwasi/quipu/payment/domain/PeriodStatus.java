package com.tarimatwasi.quipu.payment.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/**
 * One period of a contract as the guest and the administrator see it.
 *
 * @param paymentId the live payment of the period, else the last rejected one, if any
 * @param rejectionReason the reason of the last rejection, only while no payment is alive (D-34d)
 */
public record PeriodStatus(
    YearMonth period,
    LocalDate dueDate,
    PeriodState state,
    @Nullable Long paymentId,
    @Nullable String rejectionReason) {}
