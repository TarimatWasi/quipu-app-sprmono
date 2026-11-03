package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNamesUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Guest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One read for the names of a page of environments (TAR-237). */
@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class GuestNamesService implements GuestNamesUseCase {

  private final GuestRepositoryPort guests;

  GuestNamesService(GuestRepositoryPort guests) {
    this.guests = guests;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, String> namesOf(Collection<Long> guestIds) {
    Map<Long, String> names = new HashMap<>();
    for (Guest guest : guests.findAllByIds(guestIds)) {
      String name = guest.fullName();
      if (name != null) {
        names.put(guest.id(), name);
      }
    }
    return names;
  }
}
