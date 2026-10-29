package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentActionFailedException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase.DocumentActionRequest;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidDocumentActionException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Channel;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Kind;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Reason;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.Why;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TAR-187 (RN-37): the order of the store and the database, and what is left of each when one of
 * them fails, which the contract tests cannot force. The transaction is simulated by running its
 * synchronizations by hand.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GuestDocumentsServiceTest {

  private static final Long GUEST = 7L;
  private static final Long DOCUMENT = 70L;
  private static final Long ACTOR = 3L;
  private static final Instant NOW = Instant.parse("2026-10-24T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));
  private static final byte[] PDF = TestPdfs.plain();
  private static final String OLD_KEY = "guest-documents/old";
  private static final String NEW_KEY = "guest-documents/new";
  private static final DocumentActionRequest UPLOAD_ERROR =
      new DocumentActionRequest("UPLOAD_ERROR", null, null, null);
  private static final Why WHY = new Why(Reason.UPLOAD_ERROR, null, null, null);
  private static final GuestDocument OLD =
      new GuestDocument(DOCUMENT, GUEST, OLD_KEY, "dni.pdf", "application/pdf", 10, NOW);
  private static final GuestDocument NEW =
      new GuestDocument(71L, GUEST, NEW_KEY, "nuevo.pdf", "application/pdf", PDF.length, NOW);

  @Mock GuestRepositoryPort guests;
  @Mock OnboardingRepositoryPort records;
  @Mock GuestDocumentActionRepositoryPort actions;
  @Mock GuestDocumentFilePort files;
  @Mock GuestDocumentPurger purger;
  @Mock PlatformTransactionManager transactions;

  private GuestDocumentsService service() {
    return new GuestDocumentsService(
        guests, records, actions, files, purger, new TransactionTemplate(transactions), CLOCK);
  }

  @BeforeEach
  void openTransaction() {
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void closeTransaction() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  private static void complete(int status) {
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (status == TransactionSynchronization.STATUS_COMMITTED) {
        sync.afterCommit();
      }
      sync.afterCompletion(status);
    }
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

  private void theDocumentIsThere() {
    when(guests.findById(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(OLD));
  }

  private void theDocumentIsLocked() {
    theDocumentIsThere();
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(OLD));
  }

  // ------------------------------------------------------------------ deletion

  private static final PendingDeletion PENDING =
      new PendingDeletion(GUEST, DOCUMENT, WHY, "application/pdf", 10, ACTOR, NOW);

  private void theDeletionIsRecorded() {
    theDocumentIsThere();
    when(purger.begin(PENDING)).thenReturn(Optional.of(PENDING));
  }

  @Test
  void aDeletionIsRecordedThenTheObjectGoesThenTheEvidenceAndTheRow() {
    theDeletionIsRecorded();
    when(purger.complete(PENDING)).thenReturn(true);

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    InOrder order = inOrder(guests, actions, transactions, files, purger);
    order.verify(guests).findById(GUEST);
    order.verify(actions).findDocument(GUEST, DOCUMENT);
    // the read ends before the store is called: no row stays locked while R2 answers
    order.verify(transactions).commit(any());
    order.verify(purger).begin(PENDING);
    order.verify(files).delete(OLD_KEY);
    order.verify(purger).complete(PENDING);
    // then what earlier actions left pending is retried, in a small round
    order.verify(purger).purge(GuestDocumentPurger.AFTER_AN_ACTION);
    verify(guests, never()).findByIdForUpdate(any());
  }

  @Test
  void aDeletionWhoseStoreFailsKeepsItsRecordSoThatItIsFinishedLater() {
    theDeletionIsRecorded();
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentUnavailableException.class);
    // the store may have removed the object before failing: the record stays for the purger
    verify(purger, never()).complete(any());
    verify(actions, never()).removePendingDeletion(any());
  }

  @Test
  void aRepeatedDeletionKeepsTheEvidenceOfTheFirstRequest() {
    theDocumentIsThere();
    PendingDeletion first =
        new PendingDeletion(
            GUEST,
            DOCUMENT,
            new Why(
                Reason.ARCO_REQUEST,
                NOW.atZone(CLOCK.getZone()).toLocalDate(),
                Channel.EMAIL,
                null),
            "application/pdf",
            10,
            99L,
            NOW.minusSeconds(3600));
    when(purger.begin(PENDING)).thenReturn(Optional.of(first));
    when(purger.complete(first)).thenReturn(true);

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    verify(purger).complete(first);
  }

  @Test
  void onlyAFailureAfterTheObjectLeftIsTheFailedAction(CapturedOutput output) {
    theDeletionIsRecorded();
    when(purger.complete(PENDING)).thenThrow(new QueryTimeoutException("SELECT secret"));

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentActionFailedException.class);
    assertThat(output)
        .contains("guest document 70 asked by user 3")
        .doesNotContain(OLD_KEY, "SELECT secret");
    verify(purger, never()).purge(anyInt());
  }

  @Test
  void aCommitThatFailsAfterTheObjectLeftIsTheFailedAction() {
    theDeletionIsRecorded();
    when(purger.complete(PENDING)).thenThrow(new TransactionSystemException("commit failed"));

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentActionFailedException.class);
  }

  @Test
  void aFailureBeforeTheObjectLeftIsAnsweredAsUsual() {
    theDocumentIsThere();
    when(purger.begin(PENDING)).thenThrow(new PessimisticLockingFailureException("lock_timeout"));

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(PessimisticLockingFailureException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aMissingGuestOrADocumentOfAnotherGuestIsTheSameNotFound() {
    when(guests.findById(GUEST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);

    when(guests.findById(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files, purger);
  }

  @Test
  void aDeletionThatLostTheRaceToAReplacementFindsItGone() {
    theDeletionIsRecorded();
    when(purger.complete(PENDING)).thenReturn(false);
    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);
  }

  /** TAR-234: the other deletion wrote the evidence first; this one writes nothing more. */
  @Test
  void aDeletionThatLostTheRaceToAnotherDeletionIsDeleted() {
    theDeletionIsRecorded();
    when(purger.complete(PENDING)).thenReturn(false);
    when(actions.wasDeleted(GUEST, DOCUMENT)).thenReturn(true);

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    verify(actions, never()).addAction(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aDeletionRecordedAndFinishedByAnotherRequestMeanwhileIsDeletedWithoutTheStore() {
    theDocumentIsThere();
    when(purger.begin(PENDING)).thenReturn(Optional.empty());
    when(actions.wasDeleted(GUEST, DOCUMENT)).thenReturn(true);

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    verifyNoInteractions(files);
    verify(purger, never()).complete(any());
  }

  @Test
  void aDeletionRecordedAndFinishedByAnotherActionMeanwhileIsNotFound() {
    theDocumentIsThere();
    when(purger.begin(PENDING)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aDocumentAlreadyDeletedBeforeIsDeletedAndWritesNothing() {
    when(guests.findById(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.empty());
    when(actions.wasDeleted(GUEST, DOCUMENT)).thenReturn(true);

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    verifyNoInteractions(files, purger);
  }

  @Test
  void anInvalidReasonIsRefusedBeforeAnyRowIsLocked() {
    assertThatThrownBy(
            () ->
                service()
                    .delete(
                        ACTOR,
                        GUEST,
                        DOCUMENT,
                        new DocumentActionRequest("ARCO_REQUEST", null, null, null)))
        .isInstanceOf(InvalidDocumentActionException.class);
    verifyNoInteractions(guests, actions, files, purger);
  }

  // ------------------------------------------------------------------ replacement

  private void theNewObjectIsStored() {
    theDocumentIsLocked();
    when(files.store(PDF, FileKind.PDF)).thenReturn(NEW_KEY);
    when(files.signedUrl(NEW_KEY, FileKind.PDF)).thenReturn("https://r2.test/signed");
  }

  @Test
  void aReplacementSwapsTheRowAndRemovesTheOldObjectAfterTheCommit() {
    theNewObjectIsStored();
    when(records.addDocument(GUEST, NEW_KEY, "nuevo.pdf", "application/pdf", PDF.length))
        .thenReturn(NEW);

    var view =
        service()
            .replace(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF));

    assertThat(view.id()).isEqualTo(71L);
    assertThat(view.url()).isEqualTo("https://r2.test/signed");
    InOrder order = inOrder(transactions, guests, files, records, actions, purger);
    // the new file goes to the store between transactions, before any row is locked
    order.verify(transactions).commit(any());
    order.verify(files).store(PDF, FileKind.PDF);
    order.verify(transactions).getTransaction(any());
    order.verify(guests).findByIdForUpdate(GUEST);
    order.verify(actions).findDocumentForUpdate(GUEST, DOCUMENT);
    order.verify(records).addDocument(GUEST, NEW_KEY, "nuevo.pdf", "application/pdf", PDF.length);
    order.verify(actions).addAction(Kind.REPLACE, OLD, 71L, WHY, ACTOR, NOW);
    order.verify(actions).removeDocument(OLD);
    order.verify(actions).addPurge(OLD_KEY);
    order.verify(transactions).commit(any());
    // after the commit: its own old object at once, then what earlier actions left pending
    order.verify(purger).purgeObjectNow(OLD_KEY);
    order.verify(purger).purge(GuestDocumentPurger.AFTER_AN_ACTION);
    verify(files, never()).delete(NEW_KEY);
  }

  @Test
  void aNewObjectIsRemovedWhenTheTransactionOfTheSwapCannotEvenStart() {
    theDocumentIsThere();
    when(files.store(PDF, FileKind.PDF)).thenReturn(NEW_KEY);
    when(transactions.getTransaction(any()))
        .thenReturn(null)
        .thenThrow(new CannotCreateTransactionException("pool"));

    assertThatThrownBy(this::replaceIt).isInstanceOf(CannotCreateTransactionException.class);

    verify(files).delete(NEW_KEY);
    verify(purger, never()).purge(anyInt());
  }

  @Test
  void aReplacementWhoseOutcomeIsUnknownLeavesTheNewObjectPendingWithoutRemovingIt() {
    theNewObjectIsStored();
    when(records.addDocument(anyLong(), anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(NEW);

    service().replace(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF));
    complete(TransactionSynchronization.STATUS_UNKNOWN);

    verify(purger).schedule(NEW_KEY);
    verify(files, never()).delete(NEW_KEY);
  }

  @Test
  void aReplacementThatDoesNotCommitRemovesTheNewObject() {
    theNewObjectIsStored();
    when(records.addDocument(anyLong(), anyString(), anyString(), anyString(), anyLong()))
        .thenThrow(new QueryTimeoutException("insert failed"));

    assertThatThrownBy(
            () ->
                service()
                    .replace(
                        ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF)))
        .isInstanceOf(QueryTimeoutException.class);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(files).delete(NEW_KEY);
    verify(files, never()).delete(OLD_KEY);
    verify(purger, never()).schedule(anyString());
  }

  @Test
  void aNewObjectThatCouldNotBeRemovedAfterARollbackIsLeftPending() {
    theNewObjectIsStored();
    when(records.addDocument(anyLong(), anyString(), anyString(), anyString(), anyLong()))
        .thenThrow(new QueryTimeoutException("insert failed"));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(NEW_KEY);

    assertThatThrownBy(
            () ->
                service()
                    .replace(
                        ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF)))
        .isInstanceOf(QueryTimeoutException.class);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(purger).schedule(NEW_KEY);

    // even the pending record failing does not throw out of the completion
    doThrow(new QueryTimeoutException("db")).when(purger).schedule(NEW_KEY);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
  }

  @Test
  void aReplacementWhoseStoreFailsChangesNothing() {
    theDocumentIsThere();
    when(files.store(PDF, FileKind.PDF)).thenThrow(new GuestDocumentStorageException("down"));

    assertThatThrownBy(
            () ->
                service()
                    .replace(
                        ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF)))
        .isInstanceOf(GuestDocumentUnavailableException.class);
    verifyNoInteractions(records);
    verify(actions, never()).addAction(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aNewObjectThatCannotBeSignedIsRemovedWithTheRollback() {
    theDocumentIsLocked();
    when(files.store(PDF, FileKind.PDF)).thenReturn(NEW_KEY);
    when(files.signedUrl(NEW_KEY, FileKind.PDF)).thenThrow(new GuestDocumentStorageException("x"));

    assertThatThrownBy(
            () ->
                service()
                    .replace(
                        ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF)))
        .isInstanceOf(GuestDocumentUnavailableException.class);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    verify(files).delete(NEW_KEY);
  }

  @Test
  void aFileThatIsNotADocumentIsRefusedBeforeAnyRowIsLocked() {
    for (byte[] content : List.of(new byte[0], new byte[] {1, 2, 3})) {
      assertThatThrownBy(
              () ->
                  service()
                      .replace(
                          ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("x", content)))
          .isInstanceOf(InvalidOnboardingInputException.class);
    }
    verifyNoInteractions(guests, actions, files);
  }

  @Test
  void aReplacementOfAMissingDocumentStoresNothing() {
    when(guests.findById(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(this::replaceIt).isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aDocumentBeingDeletedIsNotReplacedAndNothingIsStored() {
    theDocumentIsThere();
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.of(PENDING));

    assertThatThrownBy(this::replaceIt).isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aDocumentDeletedWhileTheNewFileWasStoredIsNotFoundAndTheNewObjectGoes() {
    theDocumentIsThere();
    when(files.store(PDF, FileKind.PDF)).thenReturn(NEW_KEY);
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(OLD));
    when(actions.pendingDeletionOf(DOCUMENT))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(PENDING));

    assertThatThrownBy(this::replaceIt).isInstanceOf(GuestDocumentNotFoundException.class);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(files).delete(NEW_KEY);
    verify(records, never())
        .addDocument(anyLong(), anyString(), anyString(), anyString(), anyLong());
  }

  @Test
  void aDocumentGoneWhileTheNewFileWasStoredIsNotFoundAndTheNewObjectGoes() {
    theDocumentIsThere();
    when(files.store(PDF, FileKind.PDF)).thenReturn(NEW_KEY);
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(this::replaceIt).isInstanceOf(GuestDocumentNotFoundException.class);
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    verify(files).delete(NEW_KEY);
  }

  private void replaceIt() {
    service().replace(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF));
  }

  // ------------------------------------------------------------------ evidence

  @Test
  void theEvidenceOfAMissingGuestIsNotFound() {
    when(guests.findById(GUEST)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().actions(GUEST, PageQuery.first()))
        .isInstanceOf(GuestNotFoundException.class);
    verifyNoInteractions(actions);
  }
}
