package com.tarimatwasi.quipu.payment.port.out;

import java.util.Collection;
import java.util.Map;

public interface PaymentEnvironmentPort {

  /** The code of each environment; an id with no environment adds nothing. */
  Map<Long, String> codesOf(Collection<Long> environmentIds);

  /**
   * Checks that the environment exists, active or not.
   *
   * @throws com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException no such one
   */
  void requireExists(Long environmentId);
}
