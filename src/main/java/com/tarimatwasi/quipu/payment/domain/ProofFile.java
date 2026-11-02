package com.tarimatwasi.quipu.payment.domain;

/**
 * The file of a voucher as it is stored: its key in the bucket, never shown (RN-22).
 *
 * @param originalName the name the browser gave it, sanitized
 */
public record ProofFile(String key, String originalName, VoucherType type, long sizeBytes) {}
