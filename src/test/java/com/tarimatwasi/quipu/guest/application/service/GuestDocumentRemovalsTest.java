package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GuestDocumentRemovalsTest {

  private final GuestRepositoryPort guests = mock(GuestRepositoryPort.class);
  private final GuestDocumentActionRepositoryPort records =
      mock(GuestDocumentActionRepositoryPort.class);
  private final GuestDocumentRemovals removals = new GuestDocumentRemovals(guests, records);

  /** A guest that is gone has no document left: the pending deletion just ends. */
  @Test
  void theDeletionOfAGuestThatIsGoneFindsNothingAndEnds() {
    PendingDeletion deletion =
        new PendingDeletion(
            5L,
            70L,
            new Why(Reason.UPLOAD_ERROR, null, null, null),
            "application/pdf",
            10,
            1L,
            Instant.EPOCH);
    when(guests.findByIdForUpdate(5L)).thenReturn(Optional.empty());

    assertThat(removals.complete(deletion)).isFalse();
    verify(records, never()).findDocumentForUpdate(5L, 70L);
    verify(records).removePendingDeletion(70L);
  }
}
