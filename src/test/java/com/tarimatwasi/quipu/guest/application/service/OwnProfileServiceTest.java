package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidOnboardingInputException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.EmergencyContactView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingConflictException;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentUpload;
import com.tarimatwasi.quipu.guest.application.port.in.OnboardingUseCase.DocumentView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileRefusedException.Reason;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.OwnProfileView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnProfileUseCase.ProfileChange;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestAccountPort.AccountState;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.OnboardingRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestDocument;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CU-20, CU-23, RN-17: the guest edits its own data; what is absent keeps its value. */
@ExtendWith(MockitoExtension.class)
class OwnProfileServiceTest {
  private final ChangeSignals changes = org.mockito.Mockito.mock(ChangeSignals.class);

  private static final Long GUEST = 7L;
  private static final Instant DONE = Instant.parse("2026-10-01T15:00:00Z");

  private static final byte[] PDF = TestPdfs.plain();

  @Mock GuestRepositoryPort guests;
  @Mock GuestAccountPort accounts;
  @Mock OnboardingRepositoryPort records;
  @Mock GuestDocumentFilePort files;

  private OwnProfileService service() {
    return new OwnProfileService(
        guests, accounts, records, new GuestDocumentUploader(records, files, changes), changes);
  }

  private static GuestDocument document(long id) {
    return new GuestDocument(id, GUEST, "key-" + id, "dni.pdf", "application/pdf", 1200L, DONE);
  }

  @Test
  void ownShowsTheDataTheAccountEmailAndTheDocumentsWithoutTheirFiles() {
    when(guests.findById(GUEST))
        .thenReturn(Optional.of(guest(GuestType.CONTRACT, GuestStatus.ACTIVE)));
    when(accounts.accountEmail(GUEST)).thenReturn(Optional.of("cuenta@example.test"));
    when(records.documentsOf(GUEST)).thenReturn(List.of(document(3L)));

    OwnProfileView view = service().own(GUEST, GuestKind.CONTRACT);

    assertThat(view.documentType()).isEqualTo("DNI");
    assertThat(view.documentNumber()).isEqualTo("12345678");
    assertThat(view.name()).isEqualTo("Ana Quispe");
    assertThat(view.emergencyContact())
        .isEqualTo(new EmergencyContactView("Rosa", "Madre", "988777666"));
    assertThat(view.email()).isEqualTo("cuenta@example.test");
    assertThat(view.documents()).extracting(DocumentView::id).containsExactly(3L);
    assertThat(view.documentsLimit()).isEqualTo(GuestDocument.MAX_PER_GUEST);
  }

  @Test
  void ownWithoutAccountEmailNorEmergencyContactGivesNulls() {
    Guest bare =
        new Guest(
            GUEST,
            DocumentType.DNI,
            "12345678",
            "Ana Quispe",
            null,
            GuestType.TEMPORARY,
            GuestStatus.ACTIVE,
            null,
            DONE,
            DONE,
            null);
    when(guests.findById(GUEST)).thenReturn(Optional.of(bare));
    when(accounts.accountEmail(GUEST)).thenReturn(Optional.empty());
    when(records.documentsOf(GUEST)).thenReturn(List.of());

    OwnProfileView view = service().own(GUEST, GuestKind.TEMPORARY);

    assertThat(view.email()).isNull();
    assertThat(view.emergencyContact()).isNull();
    assertThat(view.phone()).isNull();
    assertThat(view.documents()).isEmpty();
  }

  @Test
  void ownOnTheRouteOfTheOtherTypeOrStillOnboardingIsRefused() {
    when(guests.findById(GUEST))
        .thenReturn(Optional.of(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE)))
        .thenReturn(Optional.of(guest(GuestType.CONTRACT, GuestStatus.ONBOARDING)));

    assertThatThrownBy(() -> service().own(GUEST, GuestKind.CONTRACT))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.OTHER_GUEST_TYPE));
    assertThatThrownBy(() -> service().own(GUEST, GuestKind.CONTRACT))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ONBOARDING_REQUIRED));
  }

  @Test
  void uploadDocumentStoresTheFileAndItsRowForAnActiveGuest() {
    locked(guest(GuestType.CONTRACT, GuestStatus.ACTIVE));
    when(records.countDocuments(GUEST)).thenReturn(4);
    when(files.store(any(), any())).thenReturn("key-9");
    when(records.addDocument(
            eq(GUEST), eq("key-9"), eq("dni.pdf"), eq("application/pdf"), anyLong()))
        .thenReturn(document(9L));

    DocumentView view =
        service().uploadDocument(GUEST, GuestKind.CONTRACT, new DocumentUpload("dni.pdf", PDF));

    assertThat(view.id()).isEqualTo(9L);
  }

  @Test
  void uploadDocumentWithFiveAlreadyIsRefusedAndStoresNothing() {
    locked(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE));
    when(records.countDocuments(GUEST)).thenReturn(GuestDocument.MAX_PER_GUEST);

    assertThatThrownBy(
            () ->
                service()
                    .uploadDocument(GUEST, GuestKind.TEMPORARY, new DocumentUpload("dni.pdf", PDF)))
        .isInstanceOfSatisfying(
            OnboardingConflictException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(OnboardingConflictException.Reason.DOCUMENT_LIMIT_REACHED));
    verify(files, never()).store(any(), any());
  }

  @Test
  void uploadDocumentOfAnInvalidFileIsRefusedBeforeTheGuestIsLocked() {
    assertThatThrownBy(
            () ->
                service()
                    .uploadDocument(
                        GUEST,
                        GuestKind.CONTRACT,
                        new DocumentUpload("nota.txt", "hola".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(InvalidOnboardingInputException.class);
    verify(guests, never()).findByIdForUpdate(any());
  }

  @Test
  void uploadDocumentWhileOnboardingIsRefusedAndStoresNothing() {
    locked(guest(GuestType.CONTRACT, GuestStatus.ONBOARDING));

    assertThatThrownBy(
            () ->
                service()
                    .uploadDocument(GUEST, GuestKind.CONTRACT, new DocumentUpload("dni.pdf", PDF)))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ONBOARDING_REQUIRED));
    verify(files, never()).store(any(), any());
  }

  private static Guest guest(GuestType type, GuestStatus status) {
    return new Guest(
        GUEST,
        DocumentType.DNI,
        "12345678",
        "Ana Quispe",
        "999111222",
        type,
        status,
        null,
        DONE,
        DONE,
        new EmergencyContact("Rosa", "Madre", "988777666"));
  }

  private void locked(Guest guest) {
    when(guests.findByIdForUpdate(GUEST)).thenReturn(Optional.of(guest));
  }

  private void savedAsIs() {
    when(guests.save(any())).thenAnswer(call -> call.getArgument(0));
    when(accounts.stateOf(any())).thenReturn(Map.of(GUEST, new AccountState(true, true)));
  }

  private Guest saved() {
    ArgumentCaptor<Guest> saved = ArgumentCaptor.captor();
    verify(guests).save(saved.capture());
    return saved.getValue();
  }

  private static ProfileChange name(String name) {
    return new ProfileChange(name, null, null);
  }

  @Test
  void onlyThePresentFieldsChangeAndTheRestKeepTheirValue() {
    Guest before = guest(GuestType.CONTRACT, GuestStatus.ACTIVE);
    locked(before);
    savedAsIs();

    GuestSummaryView view =
        service().update(GUEST, GuestKind.CONTRACT, new ProfileChange(null, "977000111", null));

    Guest after = saved();
    assertThat(after.phone()).isEqualTo("977000111");
    assertThat(after).usingRecursiveComparison().ignoringFields("phone").isEqualTo(before);
    assertThat(view.name()).isEqualTo("Ana Quispe");
    assertThat(view.hasLoginAccess()).isTrue();
  }

  @Test
  void nameAndPhoneChangeAndTheDocumentTheTypeAndTheStateStay() {
    Guest before = guest(GuestType.TEMPORARY, GuestStatus.ACTIVE);
    locked(before);
    savedAsIs();

    service()
        .update(
            GUEST,
            GuestKind.TEMPORARY,
            new ProfileChange(
                "Ana Maria", "977000111", new EmergencyContactView("Luis", null, "966555444")));

    Guest after = saved();
    assertThat(after.fullName()).isEqualTo("Ana Maria");
    assertThat(after.phone()).isEqualTo("977000111");
    assertThat(after.emergencyContact()).isEqualTo(new EmergencyContact("Luis", null, "966555444"));
    assertThat(after)
        .usingRecursiveComparison()
        .ignoringFields("fullName", "phone", "emergencyContact")
        .isEqualTo(before);
  }

  @Test
  void anEmergencyContactWithoutAnyFieldRemovesIt() {
    locked(guest(GuestType.CONTRACT, GuestStatus.ACTIVE));
    savedAsIs();

    service()
        .update(
            GUEST,
            GuestKind.CONTRACT,
            new ProfileChange(null, null, new EmergencyContactView(null, null, null)));

    assertThat(saved().emergencyContact()).isNull();
  }

  @Test
  void anInactiveGuestThatCompletedTheOnboardingMayStillEditIt() {
    locked(guest(GuestType.CONTRACT, GuestStatus.INACTIVE));
    savedAsIs();

    service().update(GUEST, GuestKind.CONTRACT, name("Ana"));

    assertThat(saved().fullName()).isEqualTo("Ana");
  }

  @Test
  void aChangeWithoutFieldsIsRefusedBeforeTheGuestIsRead() {
    assertThatThrownBy(
            () -> service().update(GUEST, GuestKind.CONTRACT, new ProfileChange(null, null, null)))
        .isInstanceOf(EmptyGuestUpdateException.class);
    verify(guests, never()).findByIdForUpdate(any());
  }

  @Test
  void theRouteOfTheOtherTypeOfGuestIsRefused() {
    locked(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE));

    assertThatThrownBy(() -> service().update(GUEST, GuestKind.CONTRACT, name("Ana")))
        .isInstanceOfSatisfying(
            OwnProfileRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.OTHER_GUEST_TYPE));
    verify(guests, never()).save(any());
  }

  @Test
  void aGuestThatHasNotCompletedTheOnboardingIsRefused() {
    for (GuestStatus status :
        new GuestStatus[] {GuestStatus.ONBOARDING, GuestStatus.PENDING_ACTIVATION}) {
      locked(guest(GuestType.CONTRACT, status));

      assertThatThrownBy(() -> service().update(GUEST, GuestKind.CONTRACT, name("Ana")))
          .as(status.name())
          .isInstanceOfSatisfying(
              OwnProfileRefusedException.class,
              e -> assertThat(e.reason()).isEqualTo(Reason.ONBOARDING_REQUIRED));
    }
    verify(guests, never()).save(any());
  }
}
