package com.tarimatwasi.quipu.guest.application;

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
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentActionFailedException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentsUseCase.DocumentActionRequest;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.InvalidDocumentActionException;
import com.tarimatwasi.quipu.guest.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.OnboardingRepositoryPort;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
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
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.of(OLD));
  }

  // ------------------------------------------------------------------ deletion

  private static final PendingDeletion PENDING =
      new PendingDeletion(GUEST, DOCUMENT, WHY, "application/pdf", 10, ACTOR, NOW);

  @Test
  void aDeletionIsRecordedThenTheObjectGoesThenTheEvidenceAndTheRow() {
    theDocumentIsThere();

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    InOrder order = inOrder(guests, actions, files, purger);
    order.verify(guests).findByIdForUpdate(GUEST);
    order.verify(actions).findDocumentForUpdate(GUEST, DOCUMENT);
    order.verify(purger).begin(PENDING);
    order.verify(files).delete(OLD_KEY);
    order.verify(actions).addAction(Kind.DELETE, OLD, null, WHY, ACTOR, NOW);
    order.verify(actions).removeDocument(OLD);
    order.verify(actions).removePendingDeletion(DOCUMENT);
    // after the commit, what earlier actions left pending is retried, in a small round
    verify(purger, never()).purge(anyInt());
    complete(TransactionSynchronization.STATUS_COMMITTED);
    verify(purger).purge(GuestDocumentPurger.AFTER_AN_ACTION);
  }

  @Test
  void aDeletionWhoseStoreFailsChangesNothingAndForgetsItsRecord() {
    theDocumentIsThere();
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentUnavailableException.class);
    verify(purger).cancel(DOCUMENT);
    verify(actions, never()).addAction(any(), any(), any(), any(), any(), any());
    verify(actions, never()).removeDocument(any());
  }

  @Test
  void aRecordThatCannotBeForgottenStillAnswersTheStoreFailure() {
    theDocumentIsThere();
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);
    doThrow(new QueryTimeoutException("db")).when(purger).cancel(DOCUMENT);

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentUnavailableException.class);
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
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.of(first));

    service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR);

    verify(purger, never()).begin(any());
    verify(actions).addAction(Kind.DELETE, OLD, null, first.why(), 99L, NOW.minusSeconds(3600));
  }

  @Test
  void aRepeatedDeletionWhoseStoreFailsKeepsItsRecord() {
    theDocumentIsThere();
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.of(PENDING));
    doThrow(new GuestDocumentStorageException("down")).when(files).delete(OLD_KEY);

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentUnavailableException.class);
    verify(purger, never()).cancel(any());
  }

  @Test
  void onlyAFailureAfterTheObjectLeftIsTheFailedAction(CapturedOutput output) {
    theDocumentIsThere();
    doThrow(new QueryTimeoutException("SELECT secret")).when(actions).removeDocument(OLD);

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentActionFailedException.class);
    assertThat(output)
        .contains("guest document 70 asked by user 3")
        .doesNotContain(OLD_KEY, "SELECT secret");
    verify(actions, never()).removePendingDeletion(any());
  }

  @Test
  void aCommitThatFailsAfterTheObjectLeftIsTheFailedAction() {
    theDocumentIsThere();
    doThrow(new TransactionSystemException("commit failed")).when(transactions).commit(any());

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentActionFailedException.class);
  }

  @Test
  void aFailureBeforeTheObjectLeftIsAnsweredAsUsual() {
    when(guests.findByIdForUpdate(GUEST))
        .thenThrow(new PessimisticLockingFailureException("lock_timeout"));

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(PessimisticLockingFailureException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aMissingGuestOrADocumentOfAnotherGuestIsTheSameNotFound() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);

    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files, purger);
  }

  @Test
  void anActionThatLostTheRaceForTheDocumentFindsItGone() {
    theDocumentIsThere();
    when(actions.addAction(Kind.DELETE, OLD, null, WHY, ACTOR, NOW))
        .thenThrow(new GuestDocumentActionExistsException(new DataIntegrityViolationException("")));

    assertThatThrownBy(() -> service().delete(ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verify(actions, never()).removeDocument(any());
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
    theDocumentIsThere();
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
    InOrder order = inOrder(files, records, actions);
    order.verify(files).store(PDF, FileKind.PDF);
    order.verify(records).addDocument(GUEST, NEW_KEY, "nuevo.pdf", "application/pdf", PDF.length);
    order.verify(actions).addAction(Kind.REPLACE, OLD, 71L, WHY, ACTOR, NOW);
    order.verify(actions).removeDocument(OLD);
    order.verify(actions).addPurge(OLD_KEY);
    verify(purger, never()).purge(anyInt());

    complete(TransactionSynchronization.STATUS_COMMITTED);
    verify(purger).purge(GuestDocumentPurger.AFTER_AN_ACTION);
    verify(files, never()).delete(NEW_KEY);
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
    theDocumentIsThere();
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
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest()));
    when(actions.findDocumentForUpdate(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service()
                    .replace(
                        ACTOR, GUEST, DOCUMENT, UPLOAD_ERROR, new DocumentUpload("nuevo.pdf", PDF)))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verifyNoInteractions(files);
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
