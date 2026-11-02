package com.tarimatwasi.quipu.environment.application.port.in;

import com.tarimatwasi.quipu.shared.occupancy.Occupancy;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** RF-01: create, read, edit and list the rentable environments. */
public interface ManageEnvironmentsUseCase {

  /** Which environments a listing returns; the operational listings use {@code ACTIVE} (RN-12). */
  enum StatusFilter {
    ACTIVE,
    INACTIVE,
    ALL
  }

  /** What kind of rentable space an environment is. */
  enum EnvironmentKind {
    ROOM,
    CABIN
  }

  /** An environment is deactivated, never deleted (RN-12). */
  enum EnvironmentState {
    ACTIVE,
    INACTIVE
  }

  /**
   * An environment as the callers see it, with its occupancy derived from the contracts and the
   * stays (RN-20, TAR-237).
   *
   * @param occupant who holds it today; null when free
   * @param nextOccupant a contract or a stay that starts after today
   * @param freeSince when it became free; null while occupied
   * @param deactivatedAt when it was deactivated; null while active
   * @param version the optimistic-locking version the edit sends back (TAR-276)
   */
  record EnvironmentView(
      Long id,
      String code,
      EnvironmentKind type,
      EnvironmentState status,
      @Nullable Occupancy occupant,
      @Nullable Occupancy nextOccupant,
      @Nullable LocalDate freeSince,
      @Nullable Instant deactivatedAt,
      long version) {}

  /**
   * One page of environments and how many are free today with nothing coming, across all pages (the
   * A2 notice, TAR-237).
   */
  record EnvironmentPage(CursorPage<EnvironmentView> page, long freeCount) {}

  /** {@code code} is trimmed before it is stored and compared. */
  record CreateCommand(String code, EnvironmentKind type) {}

  /**
   * Only the present fields change; at least one must be present. {@code version} is the one the
   * client loaded: a different one is a concurrent change and nothing is saved (TAR-276).
   */
  record UpdateCommand(long version, @Nullable String code, @Nullable EnvironmentKind type) {}

  /**
   * The new environment is always ACTIVE.
   *
   * @throws EnvironmentCodeTakenException if another environment has the code
   */
  EnvironmentView create(CreateCommand command);

  /**
   * Edits the present fields.
   *
   * @throws EnvironmentNotFoundException if there is no environment with that id
   * @throws EnvironmentCodeTakenException if another environment has the new code
   * @throws EmptyEnvironmentUpdateException if no field is present
   * @throws org.springframework.dao.OptimisticLockingFailureException if the version is not the
   *     current one
   */
  EnvironmentView update(Long id, UpdateCommand command);

  /**
   * Reads one environment, active or not.
   *
   * @throws EnvironmentNotFoundException if there is no environment with that id
   */
  EnvironmentView get(Long id);

  /**
   * Takes the environment out of the operational listings, keeping its history (RN-12). Doing it to
   * an inactive environment changes nothing.
   *
   * @throws EnvironmentNotFoundException if there is no environment with that id
   */
  void deactivate(Long id);

  /**
   * Brings an inactive environment back to the operational listings. Doing it to an active
   * environment changes nothing.
   *
   * @throws EnvironmentNotFoundException if there is no environment with that id
   */
  void reactivate(Long id);

  /**
   * Lists one page of the environments of the filter, ordered by code and read by last index
   * (BE-SPR-WEB-08). With {@code freeFrom}, only the active environments free for the whole range
   * come (TAR-237, to choose one for a stay or a contract), all of them in one page: there are few.
   *
   * @param freeFrom first day the environment must be free; null for no such filter
   * @param freeTo last day it must be free; null for an open-ended range
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  EnvironmentPage list(
      StatusFilter filter,
      PageQuery page,
      @Nullable LocalDate freeFrom,
      @Nullable LocalDate freeTo);

  /** The code of each environment; an id with no environment adds nothing. */
  Map<Long, String> codesOf(Collection<Long> ids);

  /** How another screen names an environment: its code and type (A3, TAR-237). */
  record EnvironmentLabel(String code, EnvironmentKind type) {}

  /** The label of each environment; an id with no environment adds nothing. */
  Map<Long, EnvironmentLabel> labelsOf(Collection<Long> ids);
}
