package com.tarimatwasi.quipu.environment.port.out;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface EnvironmentRepositoryPort {

  /**
   * Stores a new ACTIVE environment.
   *
   * @throws EnvironmentCodeAlreadyExistsException if another environment has the code
   */
  Environment insert(String code, EnvironmentType type);

  Optional<Environment> findById(Long id);

  /** Ordered by code; {@code status == null} returns every environment. */
  List<Environment> findAll(@Nullable EnvironmentStatus status);

  /**
   * Changes the present fields of the environment; empty if there is none with that id.
   *
   * @throws EnvironmentCodeAlreadyExistsException if another environment has the new code
   */
  Optional<Environment> update(Long id, @Nullable String code, @Nullable EnvironmentType type);

  /** Sets the status of the environment; false if there is none with that id. */
  boolean updateStatus(Long id, EnvironmentStatus status);
}
