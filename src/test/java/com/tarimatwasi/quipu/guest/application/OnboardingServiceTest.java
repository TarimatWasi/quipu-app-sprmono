package com.tarimatwasi.quipu.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestConsent;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.in.GuestDocumentUnavailableException;
import com.tarimatwasi.quipu.guest.port.in.GuestEmailTakenException;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.port.in.OnboardingUseCase.PersonalData;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** TAR-137: what the contract tests cannot force, such as a failed insert after the upload. */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

  private static final Long GUEST = 7L;
  private static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("America/Lima"));
  private static final byte[] PDF = "%PDF-1.7\n%x".getBytes(StandardCharsets.US_ASCII);

  @Mock GuestRepositoryPort guests;
  @Mock OnboardingRepositoryPort records;
  @Mock GuestDocumentFilePort files;

  private final ConsentTexts consents = new ConsentTexts("borrador-2026-10");

  private OnboardingService service() {
    return new OnboardingService(guests, records, files, consents, CLOCK);
  }

  private static Guest onboarding(@Nullable Instant personalData, @Nullable Instant documents) {
    return new Guest(
        GUEST,
        DocumentType.DNI,
        "12345678",
        "Ana",
        null,
        "1",
        GuestType.CONTRACT,
        GuestStatus.ONBOARDING,
        null,
        personalData,
        documents,
        null);
  }

  @Test
  void aDocumentWhoseRowIsNotSavedIsRemovedFromTheBucket() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(onboarding(NOW, null)));
    when(files.store(PDF, FileKind.PDF)).thenReturn("guest-documents/k");
    when(records.addDocument(anyLong(), anyString(), anyString(), anyString(), anyLong()))
        .thenThrow(new IllegalStateException("insert failed"));

    assertThatThrownBy(() -> service().uploadDocument(GUEST, new DocumentUpload("d.pdf", PDF)))
        .isInstanceOf(IllegalStateException.class);
    verify(files).delete("guest-documents/k");
  }

  @Test
  void aBucketThatFailsLeavesNothingAndIsReportedAsUnavailable() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(onboarding(NOW, null)));
    when(files.store(any(), any())).thenThrow(new GuestDocumentStorageException("down"));

    assertThatThrownBy(() -> service().uploadDocument(GUEST, new DocumentUpload("d.pdf", PDF)))
        .isInstanceOf(GuestDocumentUnavailableException.class);
  }

  @Test
  void aContactEmailOfAnotherGuestIsTheConflictItIs() {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(onboarding(null, null)));
    when(guests.save(any()))
        .thenThrow(new GuestEmailAlreadyExistsException(new IllegalStateException("unique")));

    assertThatThrownBy(
            () ->
                service()
                    .savePersonalData(GUEST, new PersonalData("Ana", "1", "a@example.com", null)))
        .isInstanceOf(GuestEmailTakenException.class);
  }

  @Test
  void theConsentKeepsTheHashOfTheTextShownAndActivatesTheGuest() {
    Guest guest = onboarding(NOW, NOW);
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest));
    when(guests.save(any())).thenAnswer(call -> call.getArgument(0));

    service().acceptConsent(GUEST, true, consents.current());

    ArgumentCaptor<GuestConsent> consent = ArgumentCaptor.forClass(GuestConsent.class);
    verify(records).addConsent(consent.capture());
    assertThat(consent.getValue())
        .isEqualTo(
            new GuestConsent(
                GUEST, consents.current(), NOW, ConsentTexts.sha256(consents.currentText())));
    verify(guests).save(eq(GuestService.withStatus(guest, GuestStatus.ACTIVE, null)));
  }

  @Test
  void aVersionAlreadyAcceptedIsNotRecordedTwiceAndStillActivatesTheGuest() {
    Guest guest = onboarding(NOW, NOW);
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest));
    when(guests.save(any())).thenAnswer(call -> call.getArgument(0));
    when(records.hasConsent(GUEST, consents.current())).thenReturn(true);

    service().acceptConsent(GUEST, true, consents.current());

    verify(records, never()).addConsent(any());
    verify(guests).save(eq(GuestService.withStatus(guest, GuestStatus.ACTIVE, null)));
  }

  @Test
  void aVersionWithoutItsTextFailsTheStartup() {
    assertThatThrownBy(() -> new ConsentTexts("no-existe"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ConsentTexts("../application"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(consents.currentText()).startsWith("BORRADOR PROVISIONAL");
  }
}
