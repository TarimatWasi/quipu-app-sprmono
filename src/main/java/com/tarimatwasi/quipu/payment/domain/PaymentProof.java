package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One proof of a payment (RN-22): a voucher with its file, or cash without one.
 *
 * @param declaredAmount what was declared for it (RN-16)
 * @param verifiedAmount what the administrator verified; {@code null} until the payment is approved
 * @param method free text (RN-28); {@code null} if none was given
 * @param file {@code null} for cash
 * @param receivedOn when the administration received the money (direct payments only)
 * @param addedAt when it was added
 */
public record PaymentProof(
    Long id,
    ProofKind kind,
    BigDecimal declaredAmount,
    @Nullable BigDecimal verifiedAmount,
    @Nullable String method,
    @Nullable ProofFile file,
    ProofAuthor uploadedBy,
    @Nullable LocalDate receivedOn,
    Instant addedAt) {}
