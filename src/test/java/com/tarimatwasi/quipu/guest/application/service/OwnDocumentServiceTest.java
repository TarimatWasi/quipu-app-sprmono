package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestDocumentsUseCase.GuestDocumentView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException.Reason;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentActionRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestDocumentAction.PendingDeletion;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** TAR-241 (RN-37): the guest opens only its own documents, with a link valid 15 minutes. */
@ExtendWith(MockitoExtension.class)
class OwnDocumentServiceTest {

  private static final Long GUEST = 7L;
  private static final Long DOCUMENT = 30L;
  private static final Instant NOW = Instant.parse("2026-11-01T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));
  private static final String SIGNED = "https://r2.test/guest-documents/k?X-Amz-Signature=s";

  @Mock GuestRepositoryPort guests;
  @Mock GuestDocumentActionRepositoryPort actions;
  @Mock GuestDocumentFilePort files;

  private OwnDocumentService service() {
    return new OwnDocumentService(guests, actions, files, CLOCK);
  }

  private static Guest guest(GuestType type, GuestStatus status) {
    return new Guest(
        GUEST,
        DocumentType.DNI,
        "12345678",
        "Ana Quispe",
        null,
        type,
        status,
        null,
        NOW,
        NOW,
        null);
  }

  private void theGuestIs(GuestType type, GuestStatus status) {
    when(guests.findById(GUEST)).thenReturn(Optional.of(guest(type, status)));
  }

  private static GuestDocument document() {
    return new GuestDocument(
        DOCUMENT, GUEST, "guest-documents/k", "dni.png", "image/png", 1200L, NOW);
  }

  @Test
  void open_ownDocument_givesALinkValidFifteenMinutes() {
    theGuestIs(GuestType.CONTRACT, GuestStatus.ACTIVE);
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(document()));
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.empty());
    when(files.signedUrl("guest-documents/k", FileKind.PNG)).thenReturn(SIGNED);

    GuestDocumentView view = service().open(GUEST, GuestKind.CONTRACT, DOCUMENT);

    assertThat(view.id()).isEqualTo(DOCUMENT);
    assertThat(view.originalName()).isEqualTo("dni.png");
    assertThat(view.url()).isEqualTo(SIGNED);
    assertThat(view.urlExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
  }

  @Test
  void open_documentOfAnotherGuestOrMissing_isNotFoundAndSignsNothing() {
    theGuestIs(GuestType.TEMPORARY, GuestStatus.ACTIVE);
    // The lookup is by the pair: a document of another guest is not found for this one.
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().open(GUEST, GuestKind.TEMPORARY, DOCUMENT))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verify(files, never()).signedUrl(any(), any());
  }

  @Test
  void open_documentBeingDeleted_isNotFoundAndSignsNothing() {
    theGuestIs(GuestType.CONTRACT, GuestStatus.ACTIVE);
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(document()));
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.of(mock(PendingDeletion.class)));

    assertThatThrownBy(() -> service().open(GUEST, GuestKind.CONTRACT, DOCUMENT))
        .isInstanceOf(GuestDocumentNotFoundException.class);
    verify(files, never()).signedUrl(any(), any());
  }

  @Test
  void open_onTheRouteOfTheOtherType_isRefusedBeforeTheDocumentIsRead() {
    theGuestIs(GuestType.TEMPORARY, GuestStatus.ACTIVE);

    assertThatThrownBy(() -> service().open(GUEST, GuestKind.CONTRACT, DOCUMENT))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.OTHER_GUEST_TYPE));
    verify(actions, never()).findDocument(anyLong(), anyLong());
  }

  @Test
  void open_whileOnboarding_isRefused() {
    theGuestIs(GuestType.CONTRACT, GuestStatus.ONBOARDING);

    assertThatThrownBy(() -> service().open(GUEST, GuestKind.CONTRACT, DOCUMENT))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ONBOARDING_REQUIRED));
  }

  @Test
  void open_whenTheLinkCannotBeSigned_isUnavailable() {
    theGuestIs(GuestType.CONTRACT, GuestStatus.ACTIVE);
    when(actions.findDocument(GUEST, DOCUMENT)).thenReturn(Optional.of(document()));
    when(actions.pendingDeletionOf(DOCUMENT)).thenReturn(Optional.empty());
    when(files.signedUrl(any(), any()))
        .thenThrow(new GuestDocumentStorageException("down", new IllegalStateException()));

    assertThatThrownBy(() -> service().open(GUEST, GuestKind.CONTRACT, DOCUMENT))
        .isInstanceOf(GuestDocumentUnavailableException.class);
  }
}
