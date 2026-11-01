package com.tarimatwasi.quipu.guest.application.port.in;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase.GuestDocumentView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;

/**
 * TAR-241 (RN-37): the guest opens one of its own documents from «Mis datos» (H5, T3) with a
 * short-lived signed link. The caller passes the guest of the session, never one named in the
 * request, so a document of another guest is never reached.
 */
public interface OwnDocumentUseCase {

  /**
   * The document of the guest of the session with a freshly signed link, valid 15 minutes and never
   * stored.
   *
   * @param area the type of guest the route belongs to
   * @throws GuestNotFoundException if there is no such guest
   * @throws OwnProfileRefusedException if the guest is of the other type or still onboarding
   * @throws GuestDocumentNotFoundException if the document is not one of that guest, is being
   *     deleted or does not exist: one answer for the three
   * @throws GuestDocumentUnavailableException if the link could not be signed
   */
  GuestDocumentView open(Long guestId, GuestKind area, Long documentId);
}
