package com.tarimatwasi.quipu.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * TAR-187 (RN-37): what an action left halfway stays recorded until a round finishes it: objects
 * without a row, and deletions whose object left the store but whose row did not.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GuestDocumentPurgerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T15:00:00Z");
  private static final Instant ASKED = NOW.minusSeconds(3600);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));
  private static final String KEY = "guest-documents/secret-a";
  private static final String OTHER = "guest-documents/secret-b";
  private static final Long GUEST = 7L;
  private static final Long DOCUMENT = 70L;
  private static final Why WHY = new Why(Reason.UPLOAD_ERROR, null, null, null);
  private static final PendingDeletion PENDING =
      new PendingDeletion(GUEST, DOCUMENT, WHY, "application/pdf", 10, 3L, ASKED);
  private static final GuestDocument ROW =
      new GuestDocument(DOCUMENT, GUEST, KEY, "dni.pdf", "application/pdf", 10, ASKED);

  @Mock GuestRepositoryPort guests;
  @Mock GuestDocumentActionRepositoryPort records;
  @Mock GuestDocumentFilePort files;
  @Mock PlatformTransactionManager transactions;

  private GuestDocumentPurger purger() {
    return new GuestDocumentPurger(guests, records, files, transactions, CLOCK);
  }

  private void nothingPending() {
    when(records.pendingPurges(100)).thenReturn(List.of());
    when(records.pendingDeletions(NOW.minus(GuestDocumentPurger.IN_COURSE), 100))
        .thenReturn(List.of());
  }

  private static Guest guest() {
    return new Guest(
        GUEST,
        DocumentType.DNI,
        "12345678",
        "Ana",
        null,
        "1",
        GuestType.CONTRACT,
        GuestStatus.ACTIVE,
        null,
        NOW,
        NOW,
        null);
  }

  @Test
  void theRecordsAreWrittenEachInItsOwnTransaction() {
    purger().schedule(KEY);
    purger().begin(PENDING);
    purger().cancel(DOCUMENT);

    verify(records).addPurge(KEY);
    verify(records).addPendingDeletion(PENDING);
    verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void anObjectNoRowUsesIsRemovedAndForgottenAndOneTheStoreKeepsStaysPending() {
    when(records.pendingPurges(100)).thenReturn(List.of(KEY, OTHER));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of());
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(KEY);

    assertThat(purger().purgePending(100)).isOne();

    verify(files).delete(OTHER);
    verify(records).removePurge(OTHER);
    verify(records, never()).removePurge(KEY);
  }

  @Test
  void anObjectARowStillUsesIsForgottenButNeverRemoved() {
    when(records.pendingPurges(100)).thenReturn(List.of(KEY));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of());
    when(records.isReferenced(KEY)).thenReturn(true);

    assertThat(purger().purgePending(100)).isZero();

    verify(files, never()).delete(KEY);
    verify(records).removePurge(KEY);
  }

  @Test
  void aPendingDeletionIsFinishedWithTheEvidenceOfWhoAskedAndWhen() {
    when(records.pendingPurges(100)).thenReturn(List.of());
    when(records.pendingDeletions(NOW.minus(GuestDocumentPurger.IN_COURSE), 100))
        .thenReturn(List.of(PENDING));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));

    assertThat(purger().purgePending(100)).isZero();

    verify(files).delete(KEY);
    verify(records).addAction(Kind.DELETE, ROW, null, WHY, 3L, ASKED);
    verify(records).removeDocument(ROW);
    verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void aPendingDeletionWhoseRowIsGoneIsOnlyForgotten() {
    when(records.pendingPurges(100)).thenReturn(List.of());
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThat(purger().purgePending(100)).isZero();

    verify(files, never()).delete(any());
    verify(records, never()).addAction(any(), any(), any(), any(), any(), any());
    verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void aPendingDeletionThatFailsStaysAndTheRoundGoesOn() {
    PendingDeletion second = new PendingDeletion(GUEST, 71L, WHY, "image/png", 5, 3L, ASKED);
    when(records.pendingPurges(100)).thenReturn(List.of());
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING, second));
    when(guests.findByIdForUpdate(GUEST))
        .thenThrow(new QueryTimeoutException("db"))
        .thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, 71L)).thenReturn(Optional.empty());

    assertThat(purger().purgePending(100)).isOne();

    verify(records, never()).removePendingDeletion(DOCUMENT);
    verify(records).removePendingDeletion(71L);
  }

  @Test
  void aPendingDeletionWhoseStoreFailsStays() {
    when(records.pendingPurges(100)).thenReturn(List.of());
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(KEY);

    assertThat(purger().purgePending(100)).isOne();

    verify(records, never()).removeDocument(any());
    verify(records, never()).removePendingDeletion(any());
  }

  @Test
  void theRoundAtStartupWarnsWithTheCountOnly(CapturedOutput output) {
    when(records.pendingPurges(100)).thenReturn(List.of(KEY));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of());
    doThrow(new GuestDocumentStorageException("R2 is not configured " + KEY))
        .when(files)
        .delete(KEY);

    purger().purgeAtStartup();

    assertThat(output).contains("1 guest document actions are still pending").doesNotContain(KEY);
  }

  @Test
  void aRoundWithNothingLeftSaysNothing(CapturedOutput output) {
    nothingPending();

    purger().purge(100);

    assertThat(output).doesNotContain("still pending");
  }

  @Test
  void aRoundThatCannotReadTheDatabaseDoesNotStopTheApplication(CapturedOutput output) {
    when(records.pendingPurges(100)).thenThrow(new QueryTimeoutException("db"));

    purger().purgeAtStartup();

    assertThat(output).contains("could not be read");
  }
}
