package com.tarimatwasi.quipu.guest.port.out;

import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.util.Collection;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface GuestRepositoryPort {

  Optional<Guest> findById(Long id);

  /**
   * Like {@link #findById} but the row stays locked until the transaction ends, so two simultaneous
   * edits of one guest are applied one by one. Needs a transaction.
   */
  Optional<Guest> findByIdForUpdate(Long id);

  /**
   * One page ordered by document type and number; {@code null} filters do not restrict.
   *
   * @throws InvalidCursorException if the cursor of the page is not one this service issued
   */
  CursorPage<Guest> findAll(
      @Nullable Collection<GuestStatus> statuses, @Nullable GuestType type, PageQuery page);

  /**
   * Stores the guest as given; it must already exist.
   *
   * @throws GuestDocumentAlreadyExistsException if another guest has its document
   */
  Guest save(Guest guest);
}
