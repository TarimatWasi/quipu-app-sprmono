package com.tarimatwasi.quipu.guest.application.port.out;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
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

  /** The guest with that document, whatever its state (RN-18). */
  Optional<Guest> findByDocument(DocumentType documentType, String documentNumber);

  /**
   * Stores a new guest pending activation (RN-31, RN-36).
   *
   * @throws GuestDocumentAlreadyExistsException if another guest has its document
   * @throws GuestEmailAlreadyExistsException if another guest has its email
   */
  Guest create(
      DocumentType documentType,
      String documentNumber,
      GuestType type,
      @Nullable String phone,
      @Nullable String email);

  /**
   * Stores the guest as given; it must already exist.
   *
   * @throws GuestDocumentAlreadyExistsException if another guest has its document
   */
  Guest save(Guest guest);
}
