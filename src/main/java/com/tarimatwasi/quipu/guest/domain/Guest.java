package com.tarimatwasi.quipu.guest.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A guest. The administrator registers only the document and the type (RN-31); the name, the phone
 * and the rest are the guest's own, completed in the onboarding (RN-33), so they can be missing.
 */
public record Guest(
    Long id,
    DocumentType documentType,
    String documentNumber,
    @Nullable String fullName,
    @Nullable String email,
    @Nullable String phone,
    GuestType type,
    GuestStatus status,
    @Nullable GuestStatus statusBeforeInactive,
    @Nullable Instant personalDataCompletedAt,
    @Nullable Instant documentsStepCompletedAt,
    @Nullable LocalDate stayStartDate,
    @Nullable LocalDate stayEndDate,
    @Nullable BigDecimal agreedAmount,
    @Nullable EmergencyContact emergencyContact) {

  /** The administrator corrects the document only while the guest has not activated (RN-36). */
  public boolean documentCanBeCorrected() {
    return status == GuestStatus.PENDING_ACTIVATION;
  }
}
