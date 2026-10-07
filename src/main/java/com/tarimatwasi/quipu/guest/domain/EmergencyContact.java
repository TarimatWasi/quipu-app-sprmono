package com.tarimatwasi.quipu.guest.domain;

import org.jspecify.annotations.Nullable;

/** Optional, and it never blocks any flow (RN-29). */
public record EmergencyContact(
    @Nullable String name, @Nullable String relationship, @Nullable String phone) {}
