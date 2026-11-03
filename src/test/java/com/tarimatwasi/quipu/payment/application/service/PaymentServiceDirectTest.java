package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.application.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.support.R2Failures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** A5.1, CU-14, CU-28: the payment the administrator registers by hand (TAR-239). */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PaymentServiceDirectTest extends PaymentServiceFixture {

  // ------------------------------------------------------------------ the payment by hand

  @Test
  void registerDirect_ofAPeriodStoresTheVoucherAndInsertsItApprovedByTheAdmin() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

    var view = service.registerDirect(forPeriod("2026-10", "800.00", PDF));

    assertThat(view.status()).isEqualTo(PaymentState.APPROVED);
    ArgumentCaptor<NewPayment> draft = ArgumentCaptor.forClass(NewPayment.class);
    var order = inOrder(files, payments);
    order.verify(files).store(PDF, VoucherType.PDF);
    order.verify(payments).insert(draft.capture());
    assertThat(draft.getValue().contractId()).isEqualTo(CONTRACT);
    assertThat(draft.getValue().stayId()).isNull();
    assertThat(draft.getValue().period()).isEqualTo(OCTOBER);
    assertThat(draft.getValue().reviewedById()).isEqualTo(ADMIN);
    assertThat(draft.getValue().reviewedAt()).isEqualTo(clock.instant());
    assertThat(draft.getValue().proofs().get(0).file())
        .isEqualTo(new ProofFile(KEY, "vaucher.pdf", VoucherType.PDF, PDF.length));
    assertThat(draft.getValue().proofs().get(0).method()).isEqualTo("Efectivo");
  }

  @Test
  void registerDirect_ofAStayInsertsItForThatStay() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    when(stays.stayOf(GUEST, STAY)).thenReturn(new StayInfo(STAY));
    lenient()
        .when(stays.staysByIds(Set.of(STAY)))
        .thenReturn(
            Map.of(
                STAY,
                new StayRef(
                    STAY,
                    LocalDate.parse("2026-10-10"),
                    LocalDate.parse("2026-10-20"),
                    new BigDecimal("150"),
                    null)));
    when(payments.findLiveByStay(STAY)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenReturn(stored(payment(PaymentStatus.APPROVED, null, STAY)));

    service.registerDirect(forStay(PDF));

    ArgumentCaptor<NewPayment> draft = ArgumentCaptor.forClass(NewPayment.class);
    verify(payments).insert(draft.capture());
    assertThat(draft.getValue().stayId()).isEqualTo(STAY);
    assertThat(draft.getValue().contractId()).isNull();
    assertThat(draft.getValue().period()).isNull();
    assertThat(draft.getValue().proofs().get(0).method()).isNull();
  }

  /** A direct payment with one voucher of 10 soles. */
  private static DirectPayment tenWith(
      @Nullable YearMonth period, @Nullable Long stay, @Nullable String method) {
    return new DirectPayment(
        ADMIN,
        GUEST,
        period,
        stay,
        null,
        List.of(
            new ProofUpload(
                ProofType.VOUCHER, BigDecimal.TEN, method, new VoucherUpload("v.pdf", PDF))));
  }

  @Test
  void registerDirect_exactlyOneOfPeriodOrStayAndAValidAmountComeBeforeAnyLookup() {
    var both = tenWith(OCTOBER, STAY, null);
    var neither = tenWith(null, null, null);

    assertThatThrownBy(() -> service.registerDirect(both))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));
    assertThatThrownBy(() -> service.registerDirect(neither))
        .isInstanceOf(InvalidPaymentException.class);
    for (String amount : new String[] {"0", "-5", "0.001", "100000000", "99999999.991"}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", amount, PDF)))
          .as(amount)
          .isInstanceOfSatisfying(
              InvalidPaymentException.class,
              e -> assertThat(e.field()).isEqualTo("proofs[0].amount"));
    }
    var longMethod = tenWith(OCTOBER, null, "m".repeat(51));
    assertThatThrownBy(() -> service.registerDirect(longMethod))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class,
            e -> assertThat(e.field()).isEqualTo("proofs[0].method"));
    verifyNoInteractions(guests, files, payments);
  }

  @Test
  void registerDirect_theLargestAmountAndAMethodOfFiftyCharactersAreAccepted() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
    var edge =
        new DirectPayment(
            ADMIN,
            GUEST,
            OCTOBER,
            null,
            null,
            java.util.List.of(
                new com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase
                    .ProofUpload(
                    com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase
                        .ProofType.VOUCHER,
                    new BigDecimal("99999999.99"),
                    "m".repeat(50),
                    new VoucherUpload("v.pdf", PDF))));

    assertThat(service.registerDirect(edge).status()).isEqualTo(PaymentState.APPROVED);
  }

  @Test
  void registerDirect_anUnknownGuestOrStayIsNotFoundBeforeTheGuestIsChecked() {
    when(guests.profileOf(GUEST)).thenThrow(new GuestNotFoundException());
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(GuestNotFoundException.class);

    doReturn(guest(false, false)).when(guests).profileOf(GUEST);
    when(stays.stayOf(GUEST, STAY)).thenThrow(new StayNotFoundException());
    assertThatThrownBy(() -> service.registerDirect(forStay(PDF)))
        .isInstanceOf(StayNotFoundException.class);
    verifyNoInteractions(files);
  }

  @Test
  void registerDirect_aGuestThatIsNotActiveIsRefusedEvenForTheAdministrator() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, false));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOfSatisfying(
            GuestNotActiveException.class, e -> assertThat(e.guestId()).isEqualTo(GUEST));
    verifyNoInteractions(files, contracts);
  }

  /** D-27: an inactive guest pays only a month it owes (October is paid, September is not). */
  @Test
  void registerDirect_anInactiveGuestPaysOnlyAMonthItOwes() {
    contractGuestWithOpenContract();
    when(guests.profileOf(GUEST))
        .thenReturn(new GuestProfile(GUEST, null, "1", true, false, true, "DNI"));
    var paid = payment(PaymentStatus.APPROVED, OCTOBER, null);
    when(payments.findAllByContract(CONTRACT)).thenReturn(List.of(paid));
    when(payments.insert(any())).thenReturn(stored(paid));
    for (var period : List.of("2026-10", "2026-11")) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod(period, "800", PDF)))
          .isInstanceOf(GuestNotActiveException.class);
    }
    assertThat(service.registerDirect(forPeriod("2026-09", "800", PDF)).status())
        .isEqualTo(PaymentState.APPROVED);
  }

  @Test
  void registerDirect_aTemporaryGuestHasStaysAndAGuestWithAContractHasPeriods() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));

    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(stays.stayOf(GUEST, STAY)).thenReturn(new StayInfo(STAY));
    lenient()
        .when(stays.staysByIds(Set.of(STAY)))
        .thenReturn(
            Map.of(
                STAY,
                new StayRef(
                    STAY,
                    LocalDate.parse("2026-10-10"),
                    LocalDate.parse("2026-10-20"),
                    new BigDecimal("150"),
                    null)));
    assertThatThrownBy(() -> service.registerDirect(forStay(PDF)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("stayId"));
    verifyNoInteractions(files);
  }

  @Test
  void registerDirect_aPeriodOutsideTheContractIsRefused() {
    contractGuestWithOpenContract();

    for (String period : new String[] {"2025-12", "2026-11", "2027-01"}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod(period, "800", PDF)))
          .as(period)
          .isInstanceOfSatisfying(
              InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));
    }
    verifyNoInteractions(files);
  }

  /**
   * TAR-192: the terms are read with the rows of the contracts locked, so a termination that
   * committed first is seen: a month after it is not chargeable, the same answer as any other.
   */
  @Test
  void registerDirect_readsTheTermsUnderTheLockAndRefusesAMonthAfterTheTermination() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.lockedContractsOf(GUEST))
        .thenReturn(List.of(contract("2026-01-01", null, Instant.parse("2026-09-30T15:00:00Z"))));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));
    verify(contracts, never()).contractsOf(any());
    verifyNoInteractions(files);
    verify(payments, never()).insert(any());
  }

  @Test
  void registerDirect_aGuestWithoutAContractHasNoPeriodToPay() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.lockedContractsOf(GUEST)).thenReturn(List.of());

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(InvalidPaymentException.class);
  }

  @Test
  void registerDirect_theFileIsCheckedByItsContentAfterThePeriodAndBeforeTheLivePayment() {
    contractGuestWithOpenContract();
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3};
    byte[] active = TestPdfs.withJavaScript();
    byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, tooBig, 0, PDF.length);

    for (byte[] content : new byte[][] {exe, active, tooBig, new byte[0]}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", content)))
          .isInstanceOf(InvalidVoucherFileException.class);
    }
    verify(payments, never()).findLiveByPeriod(any(), any());
    verifyNoInteractions(files);
  }

  @Test
  void registerDirect_anApprovedPaymentAndAPendingVoucherAreDifferentConflicts() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER))
        .thenReturn(Optional.of(payment(PaymentStatus.APPROVED, OCTOBER, null)))
        .thenReturn(Optional.of(payment(PaymentStatus.PENDING, OCTOBER, null)));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    verifyNoInteractions(files);
  }

  @Test
  void registerDirect_aStayWithALivePaymentIsAConflictToo() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    when(stays.stayOf(GUEST, STAY)).thenReturn(new StayInfo(STAY));
    lenient()
        .when(stays.staysByIds(Set.of(STAY)))
        .thenReturn(
            Map.of(
                STAY,
                new StayRef(
                    STAY,
                    LocalDate.parse("2026-10-10"),
                    LocalDate.parse("2026-10-20"),
                    new BigDecimal("150"),
                    null)));
    when(payments.findLiveByStay(STAY))
        .thenReturn(Optional.of(payment(PaymentStatus.APPROVED, null, STAY)));

    assertThatThrownBy(() -> service.registerDirect(forStay(PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
  }

  @Test
  void registerDirect_ifTheInsertFailsTheStoredObjectIsRemoved() {
    contractGuestWithOpenContract(); // no live payment: Mockito answers an empty Optional
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insert(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
    verify(files).delete(KEY);
  }

  @Test
  void
      registerDirect_ifAnotherRequestGotThePeriodFirstTheRightConflictIsAnsweredAndTheObjectRemoved() {
    contractGuestWithOpenContract(); // no live payment: Mockito answers an empty Optional
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any()))
        .thenThrow(new LivePaymentExistsException(PaymentStatus.PENDING))
        .thenThrow(new LivePaymentExistsException(PaymentStatus.APPROVED));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
    verify(files, org.mockito.Mockito.times(2)).delete(KEY);
  }

  @Test
  void registerDirect_aCleanupThatFailsDoesNotHideTheFailureThatCausedIt(CapturedOutput output) {
    contractGuestWithOpenContract(); // no live payment: Mockito answers an empty Optional
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insert(any())).thenThrow(failure);
    var cleanup = new PaymentFileStorageException("down", R2Failures.clientFailure(KEY));
    doThrow(cleanup).when(files).delete(KEY);
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
    assertThat(output)
        .contains("guest " + GUEST + " stays in the bucket", "SdkClientException")
        .doesNotContain(KEY, R2Failures.HOST);
  }

  @Test
  void registerDirect_ifTheBucketFailsNothingIsInserted() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenThrow(new PaymentFileStorageException("down"));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentFileUnavailableException.class);
    verify(payments, never()).insert(any());
    verify(files, never()).delete(any());
  }

  @Test
  void registerDirect_aBlankPaymentMethodIsStoredAsNothing() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
    var blank =
        new DirectPayment(
            ADMIN,
            GUEST,
            OCTOBER,
            null,
            null,
            java.util.List.of(
                new com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase
                    .ProofUpload(
                    com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase
                        .ProofType.VOUCHER,
                    new BigDecimal("800"),
                    "   ",
                    new VoucherUpload("v.pdf", PDF))));

    service.registerDirect(blank);

    ArgumentCaptor<NewPayment> draft = ArgumentCaptor.forClass(NewPayment.class);
    verify(payments).insert(draft.capture());
    assertThat(draft.getValue().proofs().get(0).method()).isNull();
  }

  @Test
  void registerDirect_anAmountInScientificNotationIsRefused() {
    for (String amount : new String[] {"8E+2", "1e2", "0.8E+3"}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", amount, PDF)))
          .as(amount)
          .isInstanceOfSatisfying(
              InvalidPaymentException.class,
              e -> assertThat(e.field()).isEqualTo("proofs[0].amount"));
    }
    verifyNoInteractions(guests, files, payments);
  }

  @Test
  void registerDirect_theAmountIsStoredWithTwoDecimals() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

    service.registerDirect(forPeriod("2026-10", "800", PDF));
    service.registerDirect(forPeriod("2026-10", "800.500", PDF));

    ArgumentCaptor<NewPayment> draft = ArgumentCaptor.forClass(NewPayment.class);
    verify(payments, org.mockito.Mockito.times(2)).insert(draft.capture());
    assertThat(draft.getAllValues())
        .allSatisfy(d -> assertThat(d.proofs().get(0).declaredAmount().scale()).isEqualTo(2));
    assertThat(draft.getAllValues().get(1).proofs().get(0).declaredAmount())
        .isEqualByComparingTo("800.50");
  }

  @Test
  void registerDirect_ifThePaymentThatBlockedTheInsertIsGoneTheConflictIsNeutral() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insert(any())).thenThrow(new LivePaymentExistsException(null));
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentConcurrentChangeException.class);
    verify(files).delete(KEY);
  }
}
