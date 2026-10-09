package com.tarimatwasi.quipu.environment.port.in;

import java.util.List;
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

  /** An environment as the callers see it. */
  record EnvironmentView(Long id, String code, EnvironmentKind type, EnvironmentState status) {}

  /** {@code code} is trimmed before it is stored and compared. */
  record CreateCommand(String code, EnvironmentKind type) {}

  /** Only the present fields change; at least one must be present. */
  record UpdateCommand(@Nullable String code, @Nullable EnvironmentKind type) {}

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

  /** Lists the environments of the filter, ordered by code. */
  List<EnvironmentView> list(StatusFilter filter);
}
