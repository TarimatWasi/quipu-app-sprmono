package com.tarimatwasi.quipu.fine.application;

import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.fine.port.in.FineGuestNotFoundException;
import com.tarimatwasi.quipu.fine.port.in.FineNotFoundException;
import com.tarimatwasi.quipu.fine.port.in.InvalidFineDescriptionException;
import com.tarimatwasi.quipu.fine.port.in.InvalidFineException;
import com.tarimatwasi.quipu.fine.port.in.ManageFinesUseCase;
import com.tarimatwasi.quipu.fine.port.out.FineGuestMissingException;
import com.tarimatwasi.quipu.fine.port.out.FineGuestPort;
import com.tarimatwasi.quipu.fine.port.out.FineRepositoryPort;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class FineService implements ManageFinesUseCase {

  /** The limit of the contract, in characters (code points) and not in UTF-16 units. */
  private static final int MAX_DESCRIPTION = 500;

  private final FineRepositoryPort fines;
  private final FineGuestPort guests;

  FineService(FineRepositoryPort fines, FineGuestPort guests) {
    this.fines = fines;
    this.guests = guests;
  }

  @Override
  @Transactional
  public FineView create(FineCommand command) {
    if (!Fine.isValidAmount(command.amount())) {
      throw new InvalidFineException();
    }
    String description = description(command.description());
    // A guest is never deleted (it is deactivated), so no lock is taken; if it were missing at the
    // insert all the same, the foreign key answers the same 404.
    Long guestId = command.guestId();
    if (guestId == null || !guests.exists(guestId)) {
      throw new FineGuestNotFoundException();
    }
    try {
      return view(fines.insert(guestId, command.amount(), command.month(), description));
    } catch (FineGuestMissingException gone) {
      throw new FineGuestNotFoundException();
    }
  }

  @Override
  @Transactional
  public void delete(Long id) {
    if (!fines.delete(id)) {
      throw new FineNotFoundException();
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<FineView> list(YearMonth month) {
    return fines.findByMonth(month).stream().map(FineService::view).toList();
  }

  private static @Nullable String description(@Nullable String description) {
    if (description == null || description.isBlank()) {
      return null;
    }
    // The limit of the contract applies to what the caller sent, before the edges are stripped
    if (description.codePointCount(0, description.length()) > MAX_DESCRIPTION) {
      throw new InvalidFineDescriptionException();
    }
    return description.strip();
  }

  private static FineView view(Fine fine) {
    return new FineView(fine.id(), fine.guestId(), fine.amount(), fine.month(), fine.description());
  }
}
