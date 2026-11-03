package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
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
      Long id,
      IdDocumentKind documentType,
      String documentNumber,
      @Nullable String name,
      GuestKind type,
      GuestState status,
      boolean hasLoginAccess) {}

  /**
   * A row of the administrator's listing (A3, TAR-237).
   *
   * @param environment what the guest occupies: a contract or a stay in course, else the next one,
   *     else, for an inactive guest, the last one; null without any
   * @param accessExpiresAt only for a guest pending activation: when its unused access ends, even
   *     if it already expired (A3.4); null otherwise
   */
  record GuestRowView(
      GuestSummaryView summary,
      @Nullable Occupancy environment,
      @Nullable Instant accessExpiresAt) {}

  /**
   * One page of the listing and the A3 notices, which count every guest whatever the filters.
   *
   * @param pendingActivationCount guests pending activation (RN-33)
   * @param onboardingCount guests in onboarding (RN-36)
   */
  record GuestListView(
      CursorPage<GuestRowView> page, long pendingActivationCount, long onboardingCount) {}

  /**
   * The 360 view of one guest (CU-06).
   *
   * @param version the optimistic-locking version the edit sends back (TAR-276)
   * @param accountEmail where the password recovery goes (A3.9); null without one
   */
  record GuestDetailView(
      GuestSummaryView summary,
      long version,
      @Nullable String accountEmail,
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
   * What the administrator may change: the document, only while the guest is pending activation.
   * The stays are managed through {@link ManageStaysUseCase}. Only the present fields change.
   */
  record UpdateCommand(
      long version, @Nullable IdDocumentKind documentType, @Nullable String documentNumber) {}

  /** A guest pending activation as the home names it (A1, RN-32). */
  record PendingGuestView(
      Long id,
      IdDocumentKind documentType,
      String documentNumber,
      @Nullable Instant accessExpiresAt) {}

  /**
   * The guests pending activation (A1 «También pendiente», RN-32, RN-36).
   *
   * @param expired how many of them have their access expired
   * @param first an expired one first, else the one whose access ends first; null without any
   */
  record NotActivatedView(long count, long expired, @Nullable PendingGuestView first) {}

  /** TAR-236: the guests pending activation, whatever the filters of the listing. */
  NotActivatedView notActivated();

  /**
   * Who a guest is for a row of the finance screens (TAR-240): a fine, a refund, a pending credit.
   *
   * @param environment the contract or stay that crosses the range asked, the latest one to start;
   *     else its last one; null if it never had one (a stay registered before TAR-237 has none)
   */
  record GuestRef(
      Long id, @Nullable String name, GuestKind type, @Nullable Occupancy environment) {}

  /**
   * The guests of those ids that exist, whatever their state, each with its environment in the
   * range of days.
   */
  Map<Long, GuestRef> refsOf(Collection<Long> ids, LocalDate from, LocalDate to);

  /**
   * Lists one page of the guests of the filter, ordered by document type and number and read by
   * last index (BE-SPR-WEB-08); {@code type == null} returns both kinds.
   *
   * @param withoutContract only the guests without a contract in force, to choose one when loading
   *     a contract (A4.1, TAR-237)
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  GuestListView list(
      StatusFilter filter, @Nullable GuestKind type, boolean withoutContract, PageQuery page);

  /**
   * Reads one guest, whatever its state.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   */
  GuestDetailView get(Long id);

  /**
   * Edits the present fields.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws org.springframework.dao.OptimisticLockingFailureException if the guest changed since
   *     the version of the command (TAR-276)
   * @throws EmptyGuestUpdateException if no field is present
   * @throws GuestDocumentLockedException if the document changes and the guest already activated
   * @throws InvalidGuestDocumentException if the number does not fit the type
   * @throws GuestDocumentTakenException if another guest has the new document
   */
  GuestSummaryView update(Long id, UpdateCommand command);

  /**
   * RF-13, RN-12: the guest becomes INACTIVE, keeps the state it had to come back to it, and its
   * account can no longer log in. The debt does not block it. Deactivating an inactive guest
   * changes nothing.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws GuestHasActiveContractException if the guest has a contract in force
   */
  void deactivate(Long id);

  /**
   * RF-13, RN-12, CU-28: the guest comes back to the state it had when it was deactivated and its
   * account can log in again. A guest that is not inactive is answered as it is, unchanged.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws com.tarimatwasi.quipu.shared.debt.GuestHasPendingDebtException if the guest owes
   *     periods (RN-25, RN-35)
   */
  GuestSummaryView reactivate(Long id);
}
