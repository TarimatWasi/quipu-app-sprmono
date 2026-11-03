package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.environment;

import com.tarimatwasi.quipu.contract.application.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentLabelsUseCase;
import com.tarimatwasi.quipu.environment.application.port.in.LockEnvironmentUseCase;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The contract module asks the environment module, through its exposed port, to lock one. */
@Component
public class EnvironmentContractAdapter implements ContractEnvironmentPort {

  private final LockEnvironmentUseCase environments;
  private final EnvironmentLabelsUseCase labels;

  EnvironmentContractAdapter(LockEnvironmentUseCase environments, EnvironmentLabelsUseCase labels) {
    this.environments = environments;
    this.labels = labels;
  }

  @Override
  public Map<Long, EnvironmentLabel> labelsOf(Collection<Long> environmentIds) {
    Map<Long, EnvironmentLabel> found = new HashMap<>();
    labels
        .labelsOf(environmentIds)
        .forEach((id, label) -> found.put(id, new EnvironmentLabel(label.code(), label.type())));
    return found;
  }

  @Override
  public boolean lock(Long environmentId) {
    return environments.lock(environmentId);
  }
}
