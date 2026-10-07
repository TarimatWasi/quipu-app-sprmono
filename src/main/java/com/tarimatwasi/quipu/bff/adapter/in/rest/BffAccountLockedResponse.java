package com.tarimatwasi.quipu.bff.adapter.in.rest;

import java.time.Instant;

/** The 423 body of the contract: the error plus the UTC instant the lock ends (TAR-131). */
public record BffAccountLockedResponse(String code, String message, Instant lockedUntil) {}
