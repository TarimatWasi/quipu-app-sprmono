package com.tarimatwasi.quipu.payment.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One decision on a payment (RN-05, TAR-230); a corrected payment can have several.
 *
 * @param decision APPROVED or REJECTED
 * @param reason the reason of a rejection; {@code null} for an approval
 * @param reviewerId the administrator who decided
 */
public record PaymentReview(
    PaymentStatus decision, @Nullable String reason, Instant at, Long reviewerId) {}
