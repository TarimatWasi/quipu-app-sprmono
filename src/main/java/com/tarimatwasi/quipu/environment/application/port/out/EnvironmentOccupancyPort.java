package com.tarimatwasi.quipu.environment.application.port.out;

public interface EnvironmentOccupancyPort {

  /** Whether a contract in force holds the environment today (RN-01, RN-20). */
  boolean isOccupied(Long environmentId);
}
