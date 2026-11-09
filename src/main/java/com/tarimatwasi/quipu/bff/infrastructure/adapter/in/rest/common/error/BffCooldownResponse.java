package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

/**
 * TAR-349: an error that says how long to wait; {@code retryAfterSeconds} repeats Retry-After so
 * that a client reads it without the header being exposed across origins.
 */
public record BffCooldownResponse(
    String code, String message, String field, long retryAfterSeconds) {}
