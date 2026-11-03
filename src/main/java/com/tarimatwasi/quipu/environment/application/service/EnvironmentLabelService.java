package com.tarimatwasi.quipu.environment.application.service;

import com.tarimatwasi.quipu.environment.application.port.in.EnvironmentLabelsUseCase;
import com.tarimatwasi.quipu.environment.application.port.out.EnvironmentRepositoryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('ADMIN', 'GUEST')") // BE-SPR-SEC-02: for the contracts of either side
public class EnvironmentLabelService implements EnvironmentLabelsUseCase {

  private final EnvironmentRepositoryPort environments;

  EnvironmentLabelService(EnvironmentRepositoryPort environments) {
    this.environments = environments;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, Label> labelsOf(Collection<Long> ids) {
    Map<Long, Label> labels = new HashMap<>();
    if (!ids.isEmpty()) {
      environments
          .findAllByIds(ids)
          .forEach(e -> labels.put(e.id(), new Label(e.code(), e.type().name())));
    }
    return labels;
  }
}
