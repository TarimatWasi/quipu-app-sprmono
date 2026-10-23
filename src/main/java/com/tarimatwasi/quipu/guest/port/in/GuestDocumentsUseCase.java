package com.tarimatwasi.quipu.guest.port.in;

import java.time.Instant;
import java.util.List;

/** CU-27, RN-37: the administrator reads the documents a guest uploaded in its onboarding. */
public interface GuestDocumentsUseCase {

  /** A document with a link that downloads it for 5 minutes. */
  record GuestDocumentView(
      Long id,
      String originalName,
      String contentType,
      long sizeBytes,
      Instant uploadedAt,
      String url,
      Instant urlExpiresAt) {}

  /**
   * The documents of the guest, oldest first, each with a freshly signed link.
   *
   * @throws GuestNotFoundException if there is no guest with that id
   * @throws GuestDocumentUnavailableException if the file store could not sign the links
   */
  List<GuestDocumentView> documents(Long guestId);
}
