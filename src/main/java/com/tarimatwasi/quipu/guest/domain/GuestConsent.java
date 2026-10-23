package com.tarimatwasi.quipu.guest.domain;

import java.time.Instant;

/** A consent the guest accepted (RN-14): the version, when, and the SHA-256 of the text shown. */
public record GuestConsent(Long guestId, String version, Instant acceptedAt, String textSha256) {}
