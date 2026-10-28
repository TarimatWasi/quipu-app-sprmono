package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.Stay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OwnGuestService implements OwnGuestUseCase {

  private final GuestRepositoryPort guests;
  private final StayRepositoryPort stays;

  OwnGuestService(GuestRepositoryPort guests, StayRepositoryPort stays) {
    this.guests = guests;
    this.stays = stays;
  }

  @Override
  @Transactional(readOnly = true)
  public OwnGuestView own(Long guestId) {
    Guest guest = guests.findById(guestId).orElseThrow(GuestNotFoundException::new);
    return new OwnGuestView(
        guest.id(),
        GuestKind.valueOf(guest.type().name()),
        GuestState.valueOf(guest.status().name()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<StayView> stays(Long guestId) {
    return stays.findAllByGuest(guestId).stream().map(OwnGuestService::view).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StayView> relevantStay(Long guestId, LocalDate today) {
    return Stay.relevantOn(stays.findAllByGuest(guestId), today).map(OwnGuestService::view);
  }

  private static StayView view(Stay stay) {
    return new StayView(stay.id(), stay.startDate(), stay.endDate(), stay.agreedAmount());
  }
}
