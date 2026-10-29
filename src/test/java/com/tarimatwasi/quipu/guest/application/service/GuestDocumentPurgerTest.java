package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentDeletionPendingException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * TAR-187 (RN-37): what an action left halfway stays recorded until a round finishes it: objects
 * without a row, and deletions whose object left the store but whose row did not.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GuestDocumentPurgerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T15:00:00Z");
  private static final Instant ASKED = NOW.minusSeconds(3600);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));
  private static final Instant MARGIN = NOW.minus(GuestDocumentPurger.IN_COURSE);
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
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
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

    assertThat(purger().begin(PENDING)).containsSame(PENDING);

    verify(records).addPurge(KEY);
    verify(records).addPendingDeletion(PENDING);
  }

  @Test
  void aDeletionAlreadyRecordedKeepsItsFirstRecord() {
    PendingDeletion first =
        new PendingDeletion(GUEST, DOCUMENT, WHY, "application/pdf", 10, 9L, NOW);
    when(records.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.of(first));

    assertThat(purger().begin(PENDING)).containsSame(first);

    verify(records, never()).addPendingDeletion(any());
  }

  @Test
  void aDeletionRecordedAtTheSameTimeByAnotherRequestKeepsThatRecord() {
    PendingDeletion other =
        new PendingDeletion(GUEST, DOCUMENT, WHY, "application/pdf", 10, 9L, NOW);
    when(records.pendingDeletionOf(DOCUMENT))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(other));
    doThrow(new GuestDocumentDeletionPendingException(new DataIntegrityViolationException("uq")))
        .when(records)
        .addPendingDeletion(PENDING);

    assertThat(purger().begin(PENDING)).containsSame(other);
  }

  /** TAR-234: the other request finished and forgot its record before this one read it again. */
  @Test
  void aDeletionRecordedAndFinishedAtTheSameTimeByAnotherRequestLeavesNothingToDo() {
    when(records.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.empty());
    doThrow(new GuestDocumentDeletionPendingException(new DataIntegrityViolationException("uq")))
        .when(records)
        .addPendingDeletion(PENDING);

    assertThat(purger().begin(PENDING)).isEmpty();
  }

  /** TAR-234: a failure that is not the rule of one pending deletion is not a race. */
  @Test
  void aDeletionThatBreaksAnotherRuleKeepsTheFailure() {
    DataIntegrityViolationException other = new DataIntegrityViolationException("note");
    doThrow(other).when(records).addPendingDeletion(PENDING);

    assertThatThrownBy(() -> purger().begin(PENDING)).isSameAs(other);
  }

  @Test
  void completingADeletionWritesTheEvidenceOfWhoAskedAndRemovesTheRow() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));

    assertThat(purger().complete(PENDING)).isTrue();

    InOrder order = inOrder(guests, records);
    order.verify(guests).findByIdForUpdate(GUEST);
    order.verify(records).findDocumentForUpdate(GUEST, DOCUMENT);
    order.verify(records).addAction(Kind.DELETE, ROW, null, WHY, 3L, ASKED);
    order.verify(records).removeDocument(ROW);
    order.verify(records).removePendingDeletion(DOCUMENT);
    verify(files, never()).delete(any());
  }

  @Test
  void completingADeletionWhoseEvidenceIsAlreadyWrittenOnlyForgetsIt() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    when(records.addAction(Kind.DELETE, ROW, null, WHY, 3L, ASKED))
        .thenThrow(new GuestDocumentActionExistsException(new DataIntegrityViolationException("")));

    assertThat(purger().complete(PENDING)).isFalse();

    verify(records, never()).removeDocument(any());
    verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void aRoundWhoseDeletionHasItsEvidenceAlreadyEndsItAndDoesNotThrow(CapturedOutput output) {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
    when(records.pendingDeletions(MARGIN, 100)).thenReturn(List.of(PENDING));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    when(records.addAction(any(), any(), any(), any(), any(), any()))
        .thenThrow(new GuestDocumentActionExistsException(new DataIntegrityViolationException("")));

    purger().purgeAtStartup();

    verify(records).removePendingDeletion(DOCUMENT);
    assertThat(output).doesNotContain("still pending");
  }

  @Test
  void theOldObjectOfAReplacementIsTriedAtOnceAndStaysPendingIfTheStoreFails() {
    purger().purgeObjectNow(KEY);
    verify(files).delete(KEY);
    verify(records).removePurge(KEY);

    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OTHER);
    purger().purgeObjectNow(OTHER);
    verify(records, never()).removePurge(OTHER);
  }

  @Test
  void completingADeletionWhoseRowIsGoneOnlyForgetsIt() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThat(purger().complete(PENDING)).isFalse();

    verify(records, never()).addAction(any(), any(), any(), any(), any(), any());
    verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void anObjectNoRowUsesIsRemovedAndForgotten() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of(KEY));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of());

    assertThat(purger().purgePending(100)).isZero();

    verify(files).delete(KEY);
    verify(records).removePurge(KEY);
  }

  @Test
  void aStoreThatFailsEndsTheRoundAndLeavesTheRestPending() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of(KEY, OTHER));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(KEY);

    assertThat(purger().purgePending(100)).isEqualTo(3);

    verify(files, never()).delete(OTHER);
    verify(records, never()).removePurge(any());
    verifyNoInteractions(guests);
  }

  @Test
  void theStoreIsNeverCalledInsideATransaction() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of(KEY));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    when(records.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    AtomicInteger open = new AtomicInteger();
    when(transactions.getTransaction(any()))
        .thenAnswer(
            call -> {
              open.incrementAndGet();
              return new SimpleTransactionStatus();
            });
    doAnswer(call -> open.decrementAndGet()).when(transactions).commit(any());
    doAnswer(
            call -> {
              assertThat(open).hasValue(0);
              return null;
            })
        .when(files)
        .delete(any());

    assertThat(purger().purgePending(100)).isZero();

    verify(files, times(2)).delete(KEY);
  }

  @Test
  void anObjectARowStillUsesIsForgottenButNeverRemoved() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of(KEY));
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of());
    when(records.isReferenced(KEY)).thenReturn(true);

    assertThat(purger().purgePending(100)).isZero();

    verify(files, never()).delete(KEY);
    verify(records).removePurge(KEY);
  }

  @Test
  void aPendingDeletionIsFinishedWithTheEvidenceOfWhoAskedAndWhen() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
    when(records.pendingDeletions(NOW.minus(GuestDocumentPurger.IN_COURSE), 100))
        .thenReturn(List.of(PENDING));
    when(records.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(records.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));

    assertThat(purger().purgePending(100)).isZero();

    InOrder order = inOrder(files, guests, records);
    order.verify(files).delete(KEY);
    order.verify(guests).findByIdForUpdate(GUEST);
    order.verify(records).addAction(Kind.DELETE, ROW, null, WHY, 3L, ASKED);
    order.verify(records).removeDocument(ROW);
    order.verify(records).removePendingDeletion(DOCUMENT);
  }

  @Test
  void aPendingDeletionWhoseRowIsGoneIsOnlyForgotten() {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    when(records.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.empty());
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
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
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
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of());
    when(records.pendingDeletions(any(), anyInt())).thenReturn(List.of(PENDING));
    when(records.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(ROW));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(KEY);

    assertThat(purger().purgePending(100)).isOne();

    verifyNoInteractions(guests);
    verify(records, never()).removeDocument(any());
    verify(records, never()).removePendingDeletion(any());
  }

  @Test
  void theRoundAtStartupWarnsWithTheCountOnly(CapturedOutput output) {
    when(records.pendingPurges(MARGIN, 100)).thenReturn(List.of(KEY));
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
    when(records.pendingPurges(MARGIN, 100)).thenThrow(new QueryTimeoutException("db"));

    purger().purgeAtStartup();

    assertThat(output).contains("could not be read");
  }
}
