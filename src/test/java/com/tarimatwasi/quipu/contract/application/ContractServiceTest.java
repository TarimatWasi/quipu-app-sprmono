package com.tarimatwasi.quipu.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.contract.port.in.ContractFileUnavailableException;
import com.tarimatwasi.quipu.contract.port.in.ContractNotFoundException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractDatesException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractException;
import com.tarimatwasi.quipu.contract.port.in.InvalidContractFileException;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.ContractFile;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.ContractState;
import com.tarimatwasi.quipu.contract.port.in.ManageContractsUseCase.NewContract;
import com.tarimatwasi.quipu.contract.port.out.ContractEnvironmentPort;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.contract.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.contract.port.out.ContractGuestPort;
import com.tarimatwasi.quipu.contract.port.out.ContractGuestPort.GuestProfile;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort.Draft;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentNotFoundException;
import com.tarimatwasi.quipu.environment.port.in.EnvironmentOccupiedException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotContractException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

  private static final Long ENVIRONMENT_ID = TestIds.next();
  private static final Long GUEST_ID = TestIds.next();
  private static final Long CONTRACT_ID = TestIds.next();
  private static final byte[] PDF = "%PDF-1.7\n%fake".getBytes(StandardCharsets.US_ASCII);
  private static final String KEY = "contracts/abc.pdf";
  private static final String URL = "https://r2.example/contracts/abc.pdf?X-Amz-Signature=s";

  // 2026-10-14 09:00 in Lima (UTC-5)
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));
  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  @Mock ContractRepositoryPort contracts;
  @Mock ContractFilePort files;
  @Mock ContractEnvironmentPort environments;
  @Mock ContractGuestPort guests;

  private ContractService service;

  @BeforeEach
  void setUp() {
    service = new ContractService(contracts, files, environments, guests, clock);
  }

  private static NewContract request(byte[] content, String name, @Nullable String end) {
    return new NewContract(
        ENVIRONMENT_ID,
        GUEST_ID,
        new BigDecimal("800.00"),
        5,
        3,
        LocalDate.parse("2026-11-01"),
        end == null ? null : LocalDate.parse(end),
        new ContractFile(name, content));
  }

  private static NewContract request() {
    return request(PDF, "contrato.pdf", null);
  }

  private static final java.time.Instant TERMINATED_AT =
      java.time.Instant.parse("2026-09-30T20:00:00Z");

  private static Contract stored(boolean terminated, @Nullable String end) {
    return new Contract(
        CONTRACT_ID,
        ENVIRONMENT_ID,
        GUEST_ID,
        LocalDate.parse("2026-01-01"),
        end == null ? null : LocalDate.parse(end),
        new BigDecimal("800.00"),
        5,
        3,
        KEY,
        "contrato.pdf",
        PDF.length,
        terminated ? TERMINATED_AT : null);
  }

  private void freeEnvironmentAndActiveContractGuest() {
    when(guests.profileOf(GUEST_ID)).thenReturn(new GuestProfile(true, true));
    when(contracts.existsInForce(ENVIRONMENT_ID, TODAY)).thenReturn(false);
  }

  // --- create ---

  @Test
  void createsTheContractAfterStoringTheFile() {
    freeEnvironmentAndActiveContractGuest();
    when(files.store(PDF)).thenReturn(KEY);
    when(contracts.insert(any())).thenReturn(stored(false, null));
    when(files.signedUrl(KEY)).thenReturn(URL);

    var created = service.create(request());

    assertThat(created.id()).isEqualTo(CONTRACT_ID);
    assertThat(created.status()).isEqualTo(ContractState.ACTIVE);
    assertThat(created.fileUrl()).isEqualTo(URL);
    var inOrder = inOrder(environments, files, contracts);
    inOrder.verify(environments).lock(ENVIRONMENT_ID);
    inOrder.verify(files).store(PDF);
    inOrder.verify(contracts).insert(any());
    ArgumentCaptor<Draft> draft = ArgumentCaptor.forClass(Draft.class);
    verify(contracts).insert(draft.capture());
    assertThat(draft.getValue().fileKey()).isEqualTo(KEY);
    assertThat(draft.getValue().sizeBytes()).isEqualTo(PDF.length);
    assertThat(draft.getValue().originalName()).isEqualTo("contrato.pdf");
    assertThat(draft.getValue().endDate()).isNull();
  }

  @Test
  void aMissingEnvironmentIsNotFoundBeforeAnythingElse() {
    org.mockito.Mockito.doThrow(new EnvironmentNotFoundException())
        .when(environments)
        .lock(ENVIRONMENT_ID);

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(EnvironmentNotFoundException.class);
    verifyNoInteractions(guests, files);
    verify(contracts, never()).insert(any());
  }

  @Test
  void aMissingGuestIsNotFound() {
    when(guests.profileOf(GUEST_ID)).thenThrow(new GuestNotFoundException());

    assertThatThrownBy(() -> service.create(request())).isInstanceOf(GuestNotFoundException.class);
    verifyNoInteractions(files);
  }

  @Test
  void aGuestThatIsNotActiveIsCheckedBeforeItsType() {
    when(guests.profileOf(GUEST_ID)).thenReturn(new GuestProfile(false, false));

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOfSatisfying(
            GuestNotActiveException.class, e -> assertThat(e.guestId()).isEqualTo(GUEST_ID));
    verifyNoInteractions(files);
  }

  @Test
  void aTemporaryGuestDoesNotTakeAContractEvenIfTheEnvironmentIsOccupied() {
    when(guests.profileOf(GUEST_ID)).thenReturn(new GuestProfile(true, false));

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(GuestNotContractException.class);
    verify(contracts, never()).existsInForce(any(), any());
    verifyNoInteractions(files);
  }

  @Test
  void anOccupiedEnvironmentRejectsTheContractWithoutTouchingTheBucket() {
    when(guests.profileOf(GUEST_ID)).thenReturn(new GuestProfile(true, true));
    when(contracts.existsInForce(ENVIRONMENT_ID, TODAY)).thenReturn(true);

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(EnvironmentOccupiedException.class);
    verifyNoInteractions(files);
    verify(contracts, never()).insert(any());
  }

  @Test
  void theOccupancyIsLookedUpOnTheDayInLima() {
    // 2026-10-15 02:00 UTC is still the 14th in Lima
    clock.advance(java.time.Duration.ofHours(12));
    when(guests.profileOf(GUEST_ID)).thenReturn(new GuestProfile(true, true));
    when(contracts.existsInForce(ENVIRONMENT_ID, TODAY)).thenReturn(true);

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(EnvironmentOccupiedException.class);
  }

  @Test
  void ifTheInsertFailsTheStoredObjectIsRemovedAndTheFailureIsKept() {
    freeEnvironmentAndActiveContractGuest();
    when(files.store(PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(contracts.insert(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.create(request())).isSameAs(failure);
    verify(files).delete(KEY);
  }

  @Test
  void ifTheCleanupAlsoFailsTheInsertFailureIsTheOneThatSurfaces() {
    freeEnvironmentAndActiveContractGuest();
    when(files.store(PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    var cleanup = new ContractFileStorageException("down");
    when(contracts.insert(any())).thenThrow(failure);
    org.mockito.Mockito.doThrow(cleanup).when(files).delete(KEY);

    assertThatThrownBy(() -> service.create(request())).isSameAs(failure);
    verify(files).delete(KEY);
  }

  @Test
  void ifTheBucketFailsNothingIsInserted() {
    freeEnvironmentAndActiveContractGuest();
    when(files.store(PDF)).thenThrow(new ContractFileStorageException("down"));

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(ContractFileUnavailableException.class);
    verify(contracts, never()).insert(any());
    verify(files, never()).delete(any());
  }

  @Test
  void aFileThatIsNotAPdfByContentIsRefusedBeforeAnyLookup() {
    byte[] exe = "MZ\u0090\u0000renamed.exe".getBytes(StandardCharsets.ISO_8859_1);

    assertThatThrownBy(() -> service.create(request(exe, "contrato.pdf", null)))
        .isInstanceOf(InvalidContractFileException.class);
    verifyNoInteractions(environments, guests, files, contracts);
  }

  @Test
  void anEmptyOrTooShortFileIsRefused() {
    assertThatThrownBy(() -> service.create(request(new byte[0], "a.pdf", null)))
        .isInstanceOf(InvalidContractFileException.class);
    assertThatThrownBy(
            () -> service.create(request("%PDF".getBytes(StandardCharsets.US_ASCII), "a", null)))
        .isInstanceOf(InvalidContractFileException.class);
  }

  @Test
  void theFileMayHaveTenMegabytesButNotOneByteMore() {
    byte[] limit = new byte[ContractService.MAX_FILE_BYTES];
    System.arraycopy(PDF, 0, limit, 0, PDF.length);
    byte[] over = new byte[ContractService.MAX_FILE_BYTES + 1];
    System.arraycopy(PDF, 0, over, 0, PDF.length);
    freeEnvironmentAndActiveContractGuest();
    when(files.store(limit)).thenReturn(KEY);
    when(contracts.insert(any())).thenReturn(stored(false, null));
    when(files.signedUrl(KEY)).thenReturn(URL);

    assertThat(service.create(request(limit, "a.pdf", null)).id()).isEqualTo(CONTRACT_ID);
    assertThatThrownBy(() -> service.create(request(over, "a.pdf", null)))
        .isInstanceOf(InvalidContractFileException.class);
  }

  @Test
  void anEndDateNotAfterTheStartIsInvalidDates() {
    assertThatThrownBy(() -> service.create(request(PDF, "a.pdf", "2026-11-01")))
        .isInstanceOf(InvalidContractDatesException.class);
    verifyNoInteractions(environments, files);
  }

  @Test
  void aFieldOutOfRangeNamesTheField() {
    var badDay =
        new NewContract(
            ENVIRONMENT_ID,
            GUEST_ID,
            new BigDecimal("800"),
            32,
            3,
            LocalDate.parse("2026-11-01"),
            null,
            new ContractFile("a.pdf", PDF));

    assertThatThrownBy(() -> service.create(badDay))
        .isInstanceOfSatisfying(
            InvalidContractException.class, e -> assertThat(e.field()).isEqualTo("paymentDay"));
  }

  // --- list, get ---

  @Test
  void listsWithTheStatusOfTheDayAndWithoutFileUrls() {
    PageQuery page = PageQuery.first();
    when(contracts.findAll(ContractStatus.ACTIVE, TODAY, page))
        .thenReturn(new CursorPage<>(List.of(stored(false, "2026-11-01")), "next"));

    var result = service.list(ContractState.ACTIVE, page);

    assertThat(result.nextCursor()).isEqualTo("next");
    assertThat(result.content())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.status()).isEqualTo(ContractState.ACTIVE);
              assertThat(c.expiringSoon()).isTrue();
            });
    verifyNoInteractions(files);
  }

  @Test
  void listsEveryContractWithoutAFilter() {
    PageQuery page = PageQuery.first();
    when(contracts.findAll(null, TODAY, page))
        .thenReturn(new CursorPage<>(List.of(stored(false, "2026-10-01")), null));

    assertThat(service.list(null, page).content())
        .singleElement()
        .satisfies(c -> assertThat(c.status()).isEqualTo(ContractState.EXPIRED));
  }

  @Test
  void getSignsTheFileUrlOnEveryRequest() {
    when(contracts.findById(CONTRACT_ID)).thenReturn(Optional.of(stored(true, null)));
    when(files.signedUrl(KEY)).thenReturn(URL);

    var view = service.get(CONTRACT_ID);

    assertThat(view.status()).isEqualTo(ContractState.TERMINATED);
    assertThat(view.fileUrl()).isEqualTo(URL);
  }

  @Test
  void getOfAnUnknownContractIsNotFound() {
    when(contracts.findById(CONTRACT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(CONTRACT_ID))
        .isInstanceOf(ContractNotFoundException.class);
  }

  // --- terminate ---

  @Test
  void terminatesAContractInForce() {
    when(contracts.findByIdForUpdate(CONTRACT_ID)).thenReturn(Optional.of(stored(false, null)));

    service.terminate(CONTRACT_ID);

    ArgumentCaptor<Contract> saved = ArgumentCaptor.forClass(Contract.class);
    verify(contracts).save(saved.capture());
    assertThat(saved.getValue().terminated()).isTrue();
    assertThat(saved.getValue().terminatedAt()).isEqualTo(clock.instant());
  }

  @Test
  void terminatingATerminatedOrExpiredContractChangesNothing() {
    when(contracts.findByIdForUpdate(CONTRACT_ID))
        .thenReturn(Optional.of(stored(true, null)))
        .thenReturn(Optional.of(stored(false, "2026-10-13")));

    service.terminate(CONTRACT_ID);
    service.terminate(CONTRACT_ID);

    verify(contracts, never()).save(any());
  }

  @Test
  void terminatingAnUnknownContractIsNotFound() {
    when(contracts.findByIdForUpdate(CONTRACT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.terminate(CONTRACT_ID))
        .isInstanceOf(ContractNotFoundException.class);
  }

  @Test
  void aBucketThatCannotSignMakesTheFileUnavailable() {
    when(contracts.findById(CONTRACT_ID)).thenReturn(Optional.of(stored(false, null)));
    when(files.signedUrl(KEY)).thenThrow(new ContractFileStorageException("down"));

    assertThatThrownBy(() -> service.get(CONTRACT_ID))
        .isInstanceOf(ContractFileUnavailableException.class)
        .hasCauseInstanceOf(ContractFileStorageException.class);
  }

  @Test
  void ifTheUrlCannotBeSignedAfterTheInsertTheStoredObjectIsRemoved() {
    freeEnvironmentAndActiveContractGuest();
    when(files.store(PDF)).thenReturn(KEY);
    when(contracts.insert(any())).thenReturn(stored(false, null));
    when(files.signedUrl(KEY)).thenThrow(new ContractFileStorageException("down"));

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(ContractFileUnavailableException.class);
    verify(files).delete(KEY);
  }
}
