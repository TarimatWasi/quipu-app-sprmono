package com.tarimatwasi.quipu.guest.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** RF-02: list, read and edit the guests the administrator registered. */
public interface ManageGuestsUseCase {

  /** Which guests a listing returns; the default hides only the inactive ones. */
  enum StatusFilter {
    CURRENT,
    PENDING_ACTIVATION,
    ONBOARDING,
    ACTIVE,
    INACTIVE,
    ALL
  }

  /** A guest with a contract or a guest of a one-time stay. */
  enum GuestKind {
    CONTRACT,
    TEMPORARY
  }

  /** Lifecycle of a guest (RN-36). */
  enum GuestState {
    PENDING_ACTIVATION,
    ONBOARDING,
    ACTIVE,
    INACTIVE
  }

  /** Type of the identity document; with its number it is the key of a person (RN-18). */
  enum IdDocumentKind {
    DNI,
    CE,
    PASSPORT
  }

  /**
   * How far the guest got in the onboarding, in the order of RN-33. An ACTIVE guest is always
   * COMPLETED.
   */
  enum OnboardingProgress {
    NOT_STARTED,
    PASSWORD_SET,
    PERSONAL_DATA_SAVED,
    DOCUMENTS_DONE,
    COMPLETED
  }

  /** The guest's emergency contact (RN-29). */
  record EmergencyContactView(
      @Nullable String name, @Nullable String relationship, @Nullable String phone) {}

  /** A guest as the listings show it. {@code name} is null until the onboarding fills it. */
  record GuestSummaryView(
      UUID id,
      IdDocumentKind documentType,
      String documentNumber,
      @Nullable String name,
      GuestKind type,
      GuestState status,
      boolean hasLoginAccess) {}

  /** The 360 view of one guest (CU-06). */
  record GuestDetailView(
      GuestSummaryView summary,
      @Nullable String phone,
      @Nullable String contactEmail,
      @Nullable EmergencyContactView emergencyContact,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable BigDecimal agreedAmount,
      OnboardingProgress onboardingProgress,
      int documentCount,
      @Nullable Instant accessExpiresAt) {}

  /**
   * What the administrator may change: the document (only while the guest is pending activation)
   * and the stay data of a temporary guest. Only the present fields change.
   */
  record UpdateCommand(
      @Nullable IdDocumentKind documentType,
      @Nullable String documentNumber,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      @Nullable BigDecimal agreedAmount) {}

  /**
   * Lists the guests of the filter, ordered by document; {@code type == null} returns both kinds.
   */
  List<GuestSummaryView> list(StatusFilter filter, @Nullable GuestKind type);

  /**
   * Reads one guest, whatever its state.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   */
  GuestDetailView get(UUID id);

  /**
   * Edits the present fields.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws EmptyGuestUpdateException if no field is present
   * @throws GuestDocumentLockedException if the document changes and the guest already activated
   * @throws InvalidGuestDocumentException if the number does not fit the type
   * @throws GuestDocumentTakenException if another guest has the new document
   * @throws InvalidGuestStayException if a stay field is not valid for this guest
   */
  GuestSummaryView update(UUID id, UpdateCommand command);
}
