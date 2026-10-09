package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface GuestRepositoryPort {

  Optional<Guest> findById(Long id);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so two simultaneous
   * edits of one guest are applied one by one. Needs a transaction.
   */
  Optional<Guest> findByIdForUpdate(Long id);

  /** Ordered by document; {@code null} filters do not restrict. */
  List<Guest> findAll(@Nullable Collection<GuestStatus> statuses, @Nullable GuestType type);

  /**
   * Stores the guest as given; it must already exist.
   *
   * @throws GuestDocumentAlreadyExistsException if another guest has its document
   */
  Guest save(Guest guest);
}
