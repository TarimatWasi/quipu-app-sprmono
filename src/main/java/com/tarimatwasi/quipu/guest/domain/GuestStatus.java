package com.tarimatwasi.quipu.guest.domain;

/** Lifecycle of a guest (RN-36). INACTIVE is reversible (RN-12). */
public enum GuestStatus {
  PENDING_ACTIVATION,
  ONBOARDING,
  ACTIVE,
  INACTIVE
}
