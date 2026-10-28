package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import java.util.UUID;

/** The 409 of a guest with a contract in force; {@code contractId} is masked (RN-12). */
public record BffGuestHasActiveContractResponse(String code, String message, UUID contractId) {}
