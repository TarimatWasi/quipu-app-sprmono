package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.environment;

import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.environment.port.in.LockEnvironmentUseCase;
import org.springframework.stereotype.Component;

/** The contract module asks the environment module, through its exposed port, to lock one. */
@Component
public class EnvironmentContractAdapter implements ContractEnvironmentPort {

  private final LockEnvironmentUseCase environments;

  EnvironmentContractAdapter(LockEnvironmentUseCase environments) {
    this.environments = environments;
  }

  @Override
  public boolean lock(Long environmentId) {
    return environments.lock(environmentId);
  }
}
