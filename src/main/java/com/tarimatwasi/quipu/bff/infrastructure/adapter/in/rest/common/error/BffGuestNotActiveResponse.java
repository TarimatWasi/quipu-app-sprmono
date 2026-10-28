package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import java.util.UUID;

/** The 409 of a guest that is not active; {@code guestId} is the masked id of the guest (RN-35). */
public record BffGuestNotActiveResponse(String code, String message, UUID guestId) {}
