package com.tarimatwasi.quipu.environment.application.port.in;

import java.util.Collection;
import java.util.Map;

/**
 * TAR-237 (A4, H2): how an environment is named on a contract («Habitación 201»), for the
 * administrator and for the guest reading its own contract. The caller decides which ids it may
 * show.
 */
public interface EnvironmentLabelsUseCase {

  /**
   * The code and the type of an environment.
   *
   * @param type ROOM or CABIN
   */
  record Label(String code, String type) {}

  /** The label of each environment; an id with no environment adds nothing. */
  Map<Long, Label> labelsOf(Collection<Long> ids);
}
