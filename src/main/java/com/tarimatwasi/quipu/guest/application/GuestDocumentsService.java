package com.tarimatwasi.quipu.guest.application;

import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@PreAuthorize("hasRole('ADMIN')") // BE-SPR-SEC-02: admin use case, besides the URL rule
public class GuestDocumentsService implements GuestDocumentsUseCase {

  private final GuestRepositoryPort guests;
  private final OnboardingRepositoryPort records;
  private final GuestDocumentFilePort files;
  private final Clock clock;

  GuestDocumentsService(
      GuestRepositoryPort guests,
      OnboardingRepositoryPort records,
      GuestDocumentFilePort files,
      Clock clock) {
    this.guests = guests;
    this.records = records;
    this.files = files;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<GuestDocumentView> documents(Long guestId) {
    if (guests.findById(guestId).isEmpty()) {
      throw new GuestNotFoundException();
    }
    Instant expiresAt = clock.instant().plus(GuestDocument.URL_VALIDITY);
    try {
      return records.documentsOf(guestId).stream()
          .map(
              document ->
                  new GuestDocumentView(
                      document.id(),
                      document.originalName(),
                      document.contentType(),
                      document.sizeBytes(),
                      document.uploadedAt(),
                      files.signedUrl(
                          document.storageKey(), FileKind.ofContentType(document.contentType())),
                      expiresAt))
          .toList();
    } catch (GuestDocumentStorageException e) {
      throw new GuestDocumentUnavailableException(e);
    }
  }
}
