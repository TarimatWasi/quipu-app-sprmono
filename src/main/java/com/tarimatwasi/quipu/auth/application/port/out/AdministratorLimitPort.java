package com.tarimatwasi.quipu.auth.application.port.out;

/** RF-22: the maximum of administrators not inactive, also checked by a reactivation. */
public interface AdministratorLimitPort {

  /** At least 1. */
  int maxAdministrators();
}
