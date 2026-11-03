package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.environment;

import com.tarimatwasi.quipu.environment.application.port.in.LockEnvironmentUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.StayEnvironmentPort;
import org.springframework.stereotype.Component;

/** The stays lock their environment through the API of the environment module (TAR-237). */
@Component
public class EnvironmentStayAdapter implements StayEnvironmentPort {

  private final LockEnvironmentUseCase environments;

  EnvironmentStayAdapter(LockEnvironmentUseCase environments) {
    this.environments = environments;
  }

  @Override
  public boolean lock(Long environmentId) {
    return environments.lock(environmentId);
  }
}
