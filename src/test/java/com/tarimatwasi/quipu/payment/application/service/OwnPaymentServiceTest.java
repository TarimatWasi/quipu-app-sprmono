package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveContractException;
import com.tarimatwasi.quipu.payment.application.port.in.NoActiveStayException;
import com.tarimatwasi.quipu.payment.application.port.in.NoStayYetException;
import com.tarimatwasi.quipu.payment.application.port.in.OtherGuestTypeException;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.ChargeState;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.PeriodVoucher;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.StayStatusView;
import com.tarimatwasi.quipu.payment.application.port.in.OwnPaymentsUseCase.StayVoucher;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.OwnGuest;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.Standing;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.VoucherDraft;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.R2Failures;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** RF-04, RF-06, CU-16, CU-17, CU-21, CU-22, RN-03, RN-09, RN-15, RN-27, RN-33, RN-35. */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OwnPaymentServiceTest {

  private static final Long GUEST = TestIds.next();
  private static final Long CONTRACT = TestIds.next();
  private static final Long OLD_CONTRACT = TestIds.next();
  private static final Long STAY = TestIds.next();
  private static final Long PAYMENT = TestIds.next();
  private static final byte[] PDF = TestPdfs.plain();
  private static final String KEY = "vouchers/abc";
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private static final Instant CREATED = Instant.parse("2026-10-14T15:00:00Z");

  // 2026-10-14 09:00 in Lima
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentFilePort files;
  @Mock PaymentGuestPort guests;
  @Mock PaymentContractPort contracts;
  @Mock PaymentStayPort stays;

  private OwnPaymentService service;

  @BeforeEach
  void setUp() {
    service = new OwnPaymentService(payments, files, guests, contracts, stays, clock);
  }

  private void guest(boolean contractType, Standing standing) {
    when(guests.ownGuest(GUEST)).thenReturn(new OwnGuest(GUEST, contractType, standing));
  }

  private static ContractInfo contract(
      Long id, String start, @Nullable String end, @Nullable Instant terminatedAt) {
    return new ContractInfo(
        id,
        TestIds.next(),
        ContractTerms.of(
            LocalDate.parse(start),
            end == null ? null : LocalDate.parse(end),
            terminatedAt,
            ZoneId.of("America/Lima"),
            5,
            3),
        new BigDecimal("800.00"));
  }

  private static Payment payment(
      Long id, PaymentStatus status, @Nullable YearMonth period, @Nullable Long stay) {
    return new Payment(
        id,
        GUEST,
        stay == null ? CONTRACT : null,
        stay,
        period,
        new BigDecimal("800.00"),
        null,
        status,
        status == PaymentStatus.REJECTED ? "Monto incompleto" : null,
        status == PaymentStatus.PENDING ? null : Instant.parse("2026-10-11T15:00:00Z"),
        status == PaymentStatus.PENDING ? null : 1L);
  }

  private static PeriodVoucher forPeriod(String period, String amount, byte[] content) {
    return new PeriodVoucher(
        GUEST,
        YearMonth.parse(period),
        new BigDecimal(amount),
        " Yape ",
        new VoucherUpload("../yape.pdf", content));
  }

  private static StayVoucher forStay(String amount, byte[] content) {
    return new StayVoucher(
        GUEST, new BigDecimal(amount), null, new VoucherUpload("v.pdf", content));
  }

  private static StayTerms stay(Long id, String start, String end) {
    return new StayTerms(id, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal("150"));
  }

  private void openContract() {
    guest(true, Standing.ACTIVE);
    when(contracts.contractsOf(GUEST))
        .thenReturn(List.of(contract(CONTRACT, "2026-01-01", null, null)));
  }

  private void storedAs(PaymentStatus status) {
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    lenient()
        .when(payments.insertPending(any()))
        .thenAnswer(
            call -> {
              VoucherDraft draft = call.getArgument(0);
              return new StoredPayment(
                  new Payment(
                      PAYMENT,
                      draft.guestId(),
                      draft.contractId(),
                      draft.stayId(),
                      draft.period(),
                      draft.amount(),
                      draft.paymentMethod(),
                      status,
                      null,
                      null,
                      null),
                  draft.voucherKey(),
                  draft.voucherType(),
                  CREATED);
            });
  }

  // ------------------------------------------------------------------ who may (RN-09, RN-33)

  @Test
  void aTemporaryGuestUsesNoneOfTheOperationsOfAGuestWithAContract() {
    when(guests.ownGuest(GUEST)).thenReturn(new OwnGuest(GUEST, false, Standing.ACTIVE));

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(OtherGuestTypeException.class);
    assertThatThrownBy(() -> service.accountStatus(GUEST))
        .isInstanceOf(OtherGuestTypeException.class);
    verifyNoInteractions(files, payments, contracts);
  }

  @Test
  void aGuestWithAContractUsesNoneOfTheOperationsOfATemporaryGuest() {
    when(guests.ownGuest(GUEST)).thenReturn(new OwnGuest(GUEST, true, Standing.ACTIVE));

    assertThatThrownBy(() -> service.uploadForStay(forStay("150", PDF)))
        .isInstanceOf(OtherGuestTypeException.class);
    assertThatThrownBy(() -> service.stayStatus(GUEST)).isInstanceOf(OtherGuestTypeException.class);
    verifyNoInteractions(files, payments, stays);
  }

  @Test
  void anInactiveGuestTakesNoNewPaymentAfterItsFieldsAreChecked() {
    guest(true, Standing.INACTIVE);

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "0", PDF)))
        .isInstanceOf(InvalidPaymentException.class);
    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(GuestNotActiveException.class);
    verifyNoInteractions(files);
  }

  // ------------------------------------------------------------------ the voucher of a period

  @Test
  void uploadForPeriod_storesAPendingVoucherOfTheSessionGuestWithoutItsUrl() {
    openContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    storedAs(PaymentStatus.PENDING);

    PaymentView view = service.uploadForPeriod(forPeriod("2026-10", "850.5", PDF));

    ArgumentCaptor<VoucherDraft> draft = ArgumentCaptor.forClass(VoucherDraft.class);
    verify(payments).insertPending(draft.capture());
    assertThat(draft.getValue())
        .isEqualTo(
            new VoucherDraft(
                GUEST,
                CONTRACT,
                null,
                OCTOBER,
                new BigDecimal("850.50"),
                "Yape",
                KEY,
                "yape.pdf",
                VoucherType.PDF,
                PDF.length));
    assertThat(view.status()).isEqualTo(PaymentState.PENDING);
    assertThat(view.guestId()).isEqualTo(GUEST);
    verify(files, never()).signedUrl(any(), any());
  }

  @Test
  void uploadForPeriod_theAmountAndTheMethodAreCheckedBeforeAnyLookup() {
    guest(true, Standing.ACTIVE);

    for (String amount : new String[] {"0", "-1", "0.001", "100000000"}) {
      assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", amount, PDF)))
          .isInstanceOf(InvalidPaymentException.class)
          .extracting(e -> ((InvalidPaymentException) e).field())
          .isEqualTo("amount");
    }
    PeriodVoucher hidden =
        new PeriodVoucher(
            GUEST, OCTOBER, BigDecimal.TEN, "Yape\u202E", new VoucherUpload("v.pdf", PDF));
    assertThatThrownBy(() -> service.uploadForPeriod(hidden))
        .extracting(e -> ((InvalidPaymentException) e).field())
        .isEqualTo("paymentMethod");
    verifyNoInteractions(contracts, files, payments);
  }

  @Test
  void uploadForPeriod_withoutAContractInForceIsRefused() {
    guest(true, Standing.ACTIVE);
    when(contracts.contractsOf(GUEST))
        .thenReturn(
            List.of(
                contract(OLD_CONTRACT, "2025-01-01", "2026-09-30", null),
                contract(CONTRACT, "2026-01-01", null, Instant.parse("2026-10-01T15:00:00Z"))));

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-09", "800", PDF)))
        .isInstanceOf(NoActiveContractException.class);
    verifyNoInteractions(files);
  }

  @Test
  void uploadForPeriod_onlyAPeriodOfTheContractInForceUpToTheCurrentMonthIsTaken() {
    guest(true, Standing.ACTIVE);
    when(contracts.contractsOf(GUEST))
        .thenReturn(
            List.of(
                contract(OLD_CONTRACT, "2025-01-01", "2025-12-31", null),
                contract(CONTRACT, "2026-03-01", null, null)));

    // before the contract, after the current month, and a month of the older contract
    for (String period : new String[] {"2026-02", "2026-11", "2025-06"}) {
      assertThatThrownBy(() -> service.uploadForPeriod(forPeriod(period, "800", PDF)))
          .isInstanceOf(InvalidPaymentException.class)
          .extracting(e -> ((InvalidPaymentException) e).field())
          .isEqualTo("period");
    }
    verifyNoInteractions(files);
  }

  @Test
  void uploadForPeriod_aRenewalLoadedAheadOfTimeDoesNotHideTheContractThatChargesTheMonth() {
    guest(true, Standing.ACTIVE);
    when(contracts.contractsOf(GUEST))
        .thenReturn(
            List.of(
                contract(CONTRACT, "2026-01-01", "2026-12-31", null),
                contract(OLD_CONTRACT, "2027-01-01", null, null)));
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    storedAs(PaymentStatus.PENDING);

    PaymentView view = service.uploadForPeriod(forPeriod("2026-10", "800", PDF));

    assertThat(view.contractId()).isEqualTo(CONTRACT);
  }

  @Test
  void uploadForPeriod_aCleanupThatFailsDoesNotHideTheFailureThatCausedIt(CapturedOutput output) {
    openContract(); // no live payment: Mockito answers an empty Optional
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insertPending(any())).thenThrow(failure);
    var cleanup = new PaymentFileStorageException("down", R2Failures.clientFailure(KEY));
    doThrow(cleanup).when(files).delete(KEY);

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
    assertThat(output)
        .contains("guest " + GUEST + " stays in the bucket", "SdkClientException")
        .doesNotContain(KEY, R2Failures.HOST);
  }

  @Test
  void uploadForPeriod_aCleanupAfterARollbackThatFailsNamesTheGuestOnly(CapturedOutput output) {
    openContract(); // no live payment: Mockito answers an empty Optional
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insertPending(any())).thenThrow(failure);
    var cleanup = new PaymentFileStorageException("down", R2Failures.clientFailure(KEY));
    doThrow(cleanup).when(files).delete(KEY);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
          .isSameAs(failure);
      verify(files, never()).delete(any()); // the transaction removes it, not the finally
      for (TransactionSynchronization sync :
          TransactionSynchronizationManager.getSynchronizations()) {
        sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(files).delete(KEY);
    assertThat(output)
        .contains("guest " + GUEST + " stays in the bucket", "SdkClientException")
        .doesNotContain(KEY, R2Failures.HOST);
  }

  @Test
  void uploadForPeriod_aFileThatIsNotAVoucherIsRefusedBeforeItIsStored() {
    openContract();

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", new byte[] {1})))
        .isInstanceOf(InvalidVoucherFileException.class);
    verifyNoInteractions(files);
  }

  @Test
  void uploadForPeriod_aPendingOrApprovedPaymentOfThePeriodIsTheConflictItIs() {
    openContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER))
        .thenReturn(Optional.of(payment(PAYMENT, PaymentStatus.PENDING, OCTOBER, null)))
        .thenReturn(Optional.of(payment(PAYMENT, PaymentStatus.APPROVED, OCTOBER, null)));

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
    verifyNoInteractions(files);
  }

  @Test
  void uploadForPeriod_aRaceLostAtTheUniqueIndexIsAConflictAndTheObjectIsRemoved() {
    openContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertPending(any()))
        .thenThrow(new LivePaymentExistsException(PaymentStatus.PENDING));

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    verify(files).delete(KEY);
  }

  @Test
  void uploadForPeriod_ifTheInsertFailsTheStoredObjectIsRemoved() {
    openContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insertPending(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.uploadForPeriod(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
    verify(files).delete(KEY);
  }

  // ------------------------------------------------------------------ the voucher of a stay

  @Test
  void uploadForStay_paysTheStayInCourse() {
    guest(false, Standing.ACTIVE);
    when(stays.staysOf(GUEST)).thenReturn(List.of(stay(STAY, "2026-10-10", "2026-10-20")));
    when(payments.findLiveByStay(STAY)).thenReturn(Optional.empty());
    storedAs(PaymentStatus.PENDING);

    PaymentView view = service.uploadForStay(forStay("150", PDF));

    ArgumentCaptor<VoucherDraft> draft = ArgumentCaptor.forClass(VoucherDraft.class);
    verify(payments).insertPending(draft.capture());
    assertThat(draft.getValue().stayId()).isEqualTo(STAY);
    assertThat(draft.getValue().contractId()).isNull();
    assertThat(draft.getValue().period()).isNull();
    assertThat(draft.getValue().paymentMethod()).isNull();
    assertThat(view.stayId()).isEqualTo(STAY);
  }

  @Test
  void uploadForStay_withoutAStayInCourseOrToComeIsRefused() {
    guest(false, Standing.ACTIVE);
    when(stays.staysOf(GUEST)).thenReturn(List.of(stay(STAY, "2026-09-01", "2026-09-10")));

    assertThatThrownBy(() -> service.uploadForStay(forStay("150", PDF)))
        .isInstanceOf(NoActiveStayException.class);
    verifyNoInteractions(files);
  }

  @Test
  void uploadForStay_aStayPaidOrUnderReviewTakesNoOtherVoucher() {
    guest(false, Standing.ACTIVE);
    when(stays.staysOf(GUEST)).thenReturn(List.of(stay(STAY, "2026-10-10", "2026-10-20")));
    when(payments.findLiveByStay(STAY))
        .thenReturn(Optional.of(payment(PAYMENT, PaymentStatus.PENDING, null, STAY)))
        .thenReturn(Optional.of(payment(PAYMENT, PaymentStatus.APPROVED, null, STAY)));

    assertThatThrownBy(() -> service.uploadForStay(forStay("150", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    assertThatThrownBy(() -> service.uploadForStay(forStay("150", PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
    verifyNoInteractions(files);
  }

  // ------------------------------------------------------------------ the state of the payments

  @Test
  void accountStatus_listsEveryChargedPeriodOfEveryContractWithTheMonthlyAmount() {
    guest(true, Standing.ACTIVE);
    when(contracts.contractsOf(GUEST))
        .thenReturn(
            List.of(
                contract(OLD_CONTRACT, "2026-07-01", "2026-08-31", null),
                contract(CONTRACT, "2026-09-01", null, null)));
    when(payments.findAllByContract(OLD_CONTRACT)).thenReturn(List.of());
    Long rejected = TestIds.next();
    when(payments.findAllByContract(CONTRACT))
        .thenReturn(
            List.of(
                payment(PAYMENT, PaymentStatus.APPROVED, YearMonth.of(2026, 9), null),
                payment(rejected, PaymentStatus.REJECTED, OCTOBER, null)));

    List<PeriodView> periods = service.accountStatus(GUEST);

    assertThat(periods)
        .extracting(PeriodView::period)
        .containsExactly(
            YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9), OCTOBER);
    assertThat(periods)
        .extracting(PeriodView::status)
        .containsExactly(
            ChargeState.OVERDUE, ChargeState.OVERDUE, ChargeState.PAID, ChargeState.OVERDUE);
    PeriodView october = periods.get(3);
    assertThat(october.contractId()).isEqualTo(CONTRACT);
    assertThat(october.amount()).isEqualByComparingTo("800");
    assertThat(october.dueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
    assertThat(october.paymentId()).isEqualTo(rejected);
    assertThat(october.rejectionReason()).isEqualTo("Monto incompleto");
  }

  @Test
  void stayStatus_showsTheRelevantStayAndAnyRejectionWithoutAStateOfItsOwn() {
    guest(false, Standing.ACTIVE);
    when(stays.relevantStayOf(GUEST, LocalDate.parse("2026-10-14")))
        .thenReturn(Optional.of(stay(STAY, "2026-10-10", "2026-10-20")));
    when(payments.findAllByStay(STAY))
        .thenReturn(List.of(payment(PAYMENT, PaymentStatus.REJECTED, null, STAY)));

    StayStatusView view = service.stayStatus(GUEST);

    assertThat(view.stayId()).isEqualTo(STAY);
    assertThat(view.status()).isEqualTo(ChargeState.UNPAID);
    assertThat(view.agreedAmount()).isEqualByComparingTo("150");
    assertThat(view.paymentId()).isEqualTo(PAYMENT);
    assertThat(view.rejectionReason()).isEqualTo("Monto incompleto");
  }

  // ------------------------------------------------------------------ the history (RF-10)

  @Test
  void history_isThePageOfTheGuestWithTheRejectionReasonEvenWhenItIsInactive() {
    guest(true, Standing.INACTIVE);
    PageQuery page = new PageQuery("c", 2);
    when(payments.findHistory(GUEST, page))
        .thenReturn(
            new CursorPage<>(
                List.of(
                    new StoredPayment(
                        payment(PAYMENT, PaymentStatus.REJECTED, OCTOBER, null),
                        KEY,
                        VoucherType.PDF,
                        CREATED)),
                "next"));

    CursorPage<PaymentView> history = service.history(GUEST, page);

    assertThat(history.nextCursor()).isEqualTo("next");
    PaymentView view = history.content().get(0);
    assertThat(view.status()).isEqualTo(PaymentState.REJECTED);
    assertThat(view.rejectionReason()).isEqualTo("Monto incompleto");
    assertThat(view.createdAt()).isEqualTo(CREATED);
  }

  @Test
  void history_isRefusedToATemporaryGuestBeforeReadingAnything() {
    guest(false, Standing.ACTIVE);
    assertThatThrownBy(() -> service.history(GUEST, PageQuery.first()))
        .isInstanceOf(OtherGuestTypeException.class);
    verifyNoInteractions(payments);
  }

  @Test
  void stayStatus_aGuestWithoutAnyStayHasNone() {
    guest(false, Standing.ACTIVE);
    when(stays.relevantStayOf(any(), any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.stayStatus(GUEST)).isInstanceOf(NoStayYetException.class);
  }
}
