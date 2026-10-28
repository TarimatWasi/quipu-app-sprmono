package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import java.util.List;

/** The 409 of a guest that owes periods; each one as {@code yyyy-MM}, oldest first (RN-25). */
public record BffGuestHasPendingDebtResponse(String code, String message, List<String> periods) {}
