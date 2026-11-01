package com.tarimatwasi.quipu.guest.application.service;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase.GuestDocumentView;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.OwnDocumentUseCase;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.time.Clock;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The document is looked up by the pair (guest of the session, document id), so the answer for a
 * document of another guest is the same 404 as for one that does not exist (QP-SPRMONO-INT-01). A
 * document whose deletion is in course gets no link: its object may already be gone.
 */
@Service
@PreAuthorize("hasRole('GUEST')") // BE-SPR-SEC-02: guest use case, besides the URL rule
public class OwnDocumentService implements OwnDocumentUseCase {

  private final GuestRepositoryPort guests;
  private final GuestDocumentActionRepositoryPort actions;
  private final GuestDocumentFilePort files;
  private final Clock clock;

  OwnDocumentService(
      GuestRepositoryPort guests,
      GuestDocumentActionRepositoryPort actions,
      GuestDocumentFilePort files,
      Clock clock) {
    this.guests = guests;
    this.actions = actions;
    this.files = files;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public GuestDocumentView open(Long guestId, GuestKind area, Long documentId) {
    OwnProfileService.allowed(
        guests.findById(guestId).orElseThrow(GuestNotFoundException::new), area);
    GuestDocument document =
        actions
            .findDocument(guestId, documentId)
            .filter(found -> actions.pendingDeletionOf(found.id()).isEmpty())
            .orElseThrow(GuestDocumentNotFoundException::new);
    String url;
    try {
      url = files.signedUrl(document.storageKey(), FileKind.ofContentType(document.contentType()));
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
    return new GuestDocumentView(
        document.id(),
        document.originalName(),
        document.contentType(),
        document.sizeBytes(),
        document.uploadedAt(),
        url,
        clock.instant().plus(GuestDocument.URL_VALIDITY));
  }
}
