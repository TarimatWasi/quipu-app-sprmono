package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestLabelsUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasAnyRole('ADMIN', 'GUEST')") // BE-SPR-SEC-02: for the contracts of either side
public class GuestLabelService implements GuestLabelsUseCase {

  private final GuestRepositoryPort guests;

  GuestLabelService(GuestRepositoryPort guests) {
    this.guests = guests;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, Label> labelsOf(Collection<Long> ids) {
    Map<Long, Label> labels = new HashMap<>();
    if (!ids.isEmpty()) {
      guests
          .findAllByIds(ids)
          .forEach(
              g ->
                  labels.put(
                      g.id(),
                      new Label(g.fullName(), g.documentType().name(), g.documentNumber())));
    }
    return labels;
  }
}
