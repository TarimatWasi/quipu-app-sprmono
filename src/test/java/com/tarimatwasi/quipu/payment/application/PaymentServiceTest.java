package com.tarimatwasi.quipu.payment.application;

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

import com.tarimatwasi.quipu.guest.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.port.in.InvalidVoucherFileException;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.port.out.PaymentEnvironmentPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.payment.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.DirectDraft;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort.StoredPayment;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.port.out.PaymentStayPort.StayInfo;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  private static final Long ADMIN = TestIds.next();
  private static final Long GUEST = TestIds.next();
  private static final Long CONTRACT = TestIds.next();
  private static final Long ENVIRONMENT = TestIds.next();
  private static final Long STAY = TestIds.next();
  private static final Long PAYMENT = TestIds.next();
  private static final byte[] PDF =
      "%PDF-1.7\n<< /Type /Catalog >>".getBytes(StandardCharsets.US_ASCII);
  private static final String KEY = "vouchers/abc";
  private static final String URL = "https://r2.test/vouchers/abc?X-Amz-Signature=s";
  private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
  private static final Instant CREATED = Instant.parse("2026-10-10T15:00:00Z");

  // 2026-10-14 09:00 in Lima
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentFilePort files;
  @Mock PaymentGuestPort guests;
  @Mock PaymentContractPort contracts;
  @Mock PaymentEnvironmentPort environments;
  @Mock PaymentStayPort stays;

  private PaymentService service;

  @BeforeEach
  void setUp() {
    service = new PaymentService(payments, files, guests, contracts, environments, stays, clock);
  }

  private static Payment payment(
      PaymentStatus status, @Nullable YearMonth period, @Nullable Long stay) {
    return new Payment(
        PAYMENT,
        GUEST,
        stay == null ? CONTRACT : null,
        stay,
        period,
        new BigDecimal("800.00"),
        "Yape",
        status,
        status == PaymentStatus.REJECTED ? "No coincide" : null,
        status == PaymentStatus.PENDING ? null : Instant.parse("2026-10-11T15:00:00Z"),
        status == PaymentStatus.PENDING ? null : ADMIN);
  }

  private static StoredPayment stored(Payment payment) {
    return new StoredPayment(payment, KEY, VoucherType.PDF, CREATED);
  }

  private static GuestProfile guest(boolean contractType, boolean active) {
    return new GuestProfile(GUEST, "Ana Quispe", "12345678", contractType, active);
  }

  private static ContractInfo contract(
      String start, @Nullable String end, @Nullable Instant terminatedAt) {
    return new ContractInfo(
        CONTRACT,
        ENVIRONMENT,
        ContractTerms.of(
            LocalDate.parse(start),
            end == null ? null : LocalDate.parse(end),
            terminatedAt,
            ZoneId.of("America/Lima"),
            5,
            3),
        new BigDecimal("800.00"));
  }

  private static DirectPayment forPeriod(String period, String amount, byte[] content) {
    return new DirectPayment(
        ADMIN,
        GUEST,
        YearMonth.parse(period),
        null,
        new BigDecimal(amount),
        "Efectivo",
        new VoucherUpload("../../vaucher.pdf", content));
  }

  private static DirectPayment forStay(byte[] content) {
    return new DirectPayment(
        ADMIN,
        GUEST,
        null,
        STAY,
        new BigDecimal("150.50"),
        null,
        new VoucherUpload("v.pdf", content));
  }

  private void contractGuestWithOpenContract() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of(contract("2026-01-01", null, null)));
    lenient().when(payments.findAllByContract(CONTRACT)).thenReturn(List.of());
  }

  // ------------------------------------------------------------------ the queue and the detail

  @Test
  void list_showsTheGuestAndTheEnvironmentOfAContractAndNothingOfATemporaryGuest() {
    Long temporaryGuest = TestIds.next();
    Payment ofContract = payment(PaymentStatus.PENDING, OCTOBER, null);
    Payment ofStay =
        new Payment(
            TestIds.next(),
            temporaryGuest,
            null,
            STAY,
            null,
            BigDecimal.TEN,
            null,
            PaymentStatus.PENDING,
            null,
            null,
            null);
    PageQuery page = PageQuery.first();
    when(payments.findQueue(PaymentStatus.PENDING, page))
        .thenReturn(new CursorPage<>(List.of(stored(ofContract), stored(ofStay)), "next"));
    when(guests.profilesOf(any()))
        .thenReturn(
            Map.of(
                GUEST,
                guest(true, true),
                temporaryGuest,
                new GuestProfile(temporaryGuest, null, "87654321", false, true)));
    when(contracts.environmentsOf(any())).thenReturn(Map.of(CONTRACT, ENVIRONMENT));
    when(environments.codesOf(any())).thenReturn(Map.of(ENVIRONMENT, "201"));

    var result = service.list(PaymentState.PENDING, page);

    assertThat(result.nextCursor()).isEqualTo("next");
    assertThat(result.content().get(0).guestName()).isEqualTo("Ana Quispe");
    assertThat(result.content().get(0).guestType()).isEqualTo(GuestKind.CONTRACT);
    assertThat(result.content().get(0).environmentCode()).isEqualTo("201");
    assertThat(result.content().get(0).period()).isEqualTo(OCTOBER);
    assertThat(result.content().get(0).createdAt()).isEqualTo(CREATED);
    assertThat(result.content().get(1).guestName()).isNull();
    assertThat(result.content().get(1).guestType()).isEqualTo(GuestKind.TEMPORARY);
    assertThat(result.content().get(1).environmentCode()).isNull();
    assertThat(result.content().get(1).period()).isNull();
  }

  @Test
  void list_withoutAFilterReadsEveryState() {
    PageQuery page = PageQuery.first();
    when(payments.findQueue(null, page)).thenReturn(new CursorPage<>(List.of(), null));
    when(guests.profilesOf(any())).thenReturn(Map.of());
    when(contracts.environmentsOf(any())).thenReturn(Map.of());
    when(environments.codesOf(any())).thenReturn(Map.of());

    assertThat(service.list(null, page).content()).isEmpty();
  }

  @Test
  void get_signsTheVoucherUrlOnEveryRequest() {
    when(payments.findById(PAYMENT))
        .thenReturn(Optional.of(stored(payment(PaymentStatus.PENDING, OCTOBER, null))));
    when(files.signedUrl(KEY, VoucherType.PDF)).thenReturn(URL);

    var detail = service.get(PAYMENT);

    assertThat(detail.voucherUrl()).isEqualTo(URL);
    assertThat(detail.payment().status()).isEqualTo(PaymentState.PENDING);
    assertThat(detail.payment().createdAt()).isEqualTo(CREATED);
  }

  @Test
  void get_ofAnUnknownPaymentIsNotFoundAndAFailingBucketIsUnavailable() {
    when(payments.findById(PAYMENT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(PAYMENT)).isInstanceOf(PaymentNotFoundException.class);

    when(payments.findById(PAYMENT))
        .thenReturn(Optional.of(stored(payment(PaymentStatus.APPROVED, OCTOBER, null))));
    when(files.signedUrl(KEY, VoucherType.PDF)).thenThrow(new PaymentFileStorageException("down"));
    assertThatThrownBy(() -> service.get(PAYMENT))
        .isInstanceOf(PaymentFileUnavailableException.class);
  }

  // ------------------------------------------------------------------ resolving

  private void pendingPayment() {
    when(payments.findByIdForUpdate(PAYMENT))
        .thenReturn(Optional.of(stored(payment(PaymentStatus.PENDING, OCTOBER, null))));
    lenient().when(payments.save(any())).thenAnswer(call -> stored(call.getArgument(0)));
  }

  @Test
  void resolve_approvesWithTheReviewerOfTheSessionAndTheClock() {
    pendingPayment();

    var view = service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null));

    assertThat(view.status()).isEqualTo(PaymentState.APPROVED);
    ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
    verify(payments).save(saved.capture());
    assertThat(saved.getValue().reviewedById()).isEqualTo(ADMIN);
    assertThat(saved.getValue().reviewedAt()).isEqualTo(clock.instant());
  }

  @Test
  void resolve_readsTheRowLockedAndIgnoresAReasonGivenWithAnApproval() {
    pendingPayment();

    var view = service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, "ignored"));

    assertThat(view.rejectionReason()).isNull();
    verify(payments).findByIdForUpdate(PAYMENT);
    verify(payments, never()).findById(any());
  }

  @Test
  void resolve_rejectsWithTheReasonWithoutItsEdgeSpaces() {
    pendingPayment();

    var view =
        service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "  Monto menor  "));

    assertThat(view.status()).isEqualTo(PaymentState.REJECTED);
    assertThat(view.rejectionReason()).isEqualTo("Monto menor");
  }

  @Test
  void resolve_aRejectionWithoutAReasonIsRefusedWhateverBlankMeans() {
    for (String blank : new String[] {null, "", "   ", " ", " "}) {
      pendingPayment();
      assertThatThrownBy(
              () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, blank)))
          .as("[" + blank + "]")
          .isInstanceOf(PaymentReasonRequiredException.class);
    }
    verify(payments, never()).save(any());
  }

  @Test
  void resolve_aLongReasonOfOnlySeparatorsIsMissingNotTooLong() {
    pendingPayment();
    String separators = " ".repeat(501);

    assertThatThrownBy(
            () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, separators)))
        .isInstanceOf(PaymentReasonRequiredException.class);
  }

  @Test
  void resolve_aReasonOfOnlyATabOrALineBreakIsInvalidNotMissing() {
    for (String reason : new String[] {"\t", "\n", "  \n ", "\u2028"}) {
      pendingPayment();
      assertThatThrownBy(
              () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, reason)))
          .isInstanceOfSatisfying(
              InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    }
  }

  @Test
  void resolve_aReasonOverFiveHundredCharactersOrWithControlCharactersIsInvalid() {
    pendingPayment();

    assertThat(
            service
                .resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "x".repeat(500)))
                .rejectionReason())
        .hasSize(500);
    pendingPayment();
    assertThatThrownBy(
            () ->
                service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "x".repeat(501))))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    assertThatThrownBy(
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "mal\u0000motivo")))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    assertThatThrownBy(
            () ->
                service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "esc\u001Bape")))
        .isInstanceOf(InvalidPaymentException.class);
  }

  @Test
  void resolve_aPaymentThatIsNoLongerPendingIsAlreadyResolved() {
    for (PaymentStatus resolved :
        new PaymentStatus[] {PaymentStatus.APPROVED, PaymentStatus.REJECTED}) {
      when(payments.findByIdForUpdate(PAYMENT))
          .thenReturn(Optional.of(stored(payment(resolved, OCTOBER, null))));

      assertThatThrownBy(
              () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null)))
          .isInstanceOf(PaymentAlreadyResolvedException.class);
      assertThatThrownBy(
              () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "Tarde")))
          .isInstanceOf(PaymentAlreadyResolvedException.class);
    }
    verify(payments, never()).save(any());
  }

  @Test
  void resolve_ofAnUnknownPaymentIsNotFound() {
    when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null)))
        .isInstanceOf(PaymentNotFoundException.class);
  }

  // ------------------------------------------------------------------ the payment by hand

  @Test
  void registerDirect_ofAPeriodStoresTheVoucherAndInsertsItApprovedByTheAdmin() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

    var view = service.registerDirect(forPeriod("2026-10", "800.00", PDF));

    assertThat(view.status()).isEqualTo(PaymentState.APPROVED);
    ArgumentCaptor<DirectDraft> draft = ArgumentCaptor.forClass(DirectDraft.class);
    var order = inOrder(files, payments);
    order.verify(files).store(PDF, VoucherType.PDF);
    order.verify(payments).insertApproved(draft.capture());
    assertThat(draft.getValue().contractId()).isEqualTo(CONTRACT);
    assertThat(draft.getValue().stayId()).isNull();
    assertThat(draft.getValue().period()).isEqualTo(OCTOBER);
    assertThat(draft.getValue().reviewedById()).isEqualTo(ADMIN);
    assertThat(draft.getValue().reviewedAt()).isEqualTo(clock.instant());
    assertThat(draft.getValue().voucherKey()).isEqualTo(KEY);
    assertThat(draft.getValue().originalName()).isEqualTo("vaucher.pdf");
    assertThat(draft.getValue().sizeBytes()).isEqualTo(PDF.length);
    assertThat(draft.getValue().paymentMethod()).isEqualTo("Efectivo");
  }

  @Test
  void registerDirect_ofAStayInsertsItForThatStay() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    when(stays.stayOf(GUEST, STAY)).thenReturn(new StayInfo(STAY));
    when(payments.findLiveByStay(STAY)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenReturn(stored(payment(PaymentStatus.APPROVED, null, STAY)));

    service.registerDirect(forStay(PDF));

    ArgumentCaptor<DirectDraft> draft = ArgumentCaptor.forClass(DirectDraft.class);
    verify(payments).insertApproved(draft.capture());
    assertThat(draft.getValue().stayId()).isEqualTo(STAY);
    assertThat(draft.getValue().contractId()).isNull();
    assertThat(draft.getValue().period()).isNull();
    assertThat(draft.getValue().paymentMethod()).isNull();
  }

  @Test
  void registerDirect_exactlyOneOfPeriodOrStayAndAValidAmountComeBeforeAnyLookup() {
    var both =
        new DirectPayment(
            ADMIN, GUEST, OCTOBER, STAY, BigDecimal.TEN, null, new VoucherUpload("v.pdf", PDF));
    var neither =
        new DirectPayment(
            ADMIN, GUEST, null, null, BigDecimal.TEN, null, new VoucherUpload("v.pdf", PDF));

    assertThatThrownBy(() -> service.registerDirect(both))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));
    assertThatThrownBy(() -> service.registerDirect(neither))
        .isInstanceOf(InvalidPaymentException.class);
    for (String amount : new String[] {"0", "-5", "0.001", "100000000", "99999999.991"}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", amount, PDF)))
          .as(amount)
          .isInstanceOfSatisfying(
              InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("amount"));
    }
    var longMethod =
        new DirectPayment(
            ADMIN,
            GUEST,
            OCTOBER,
            null,
            BigDecimal.TEN,
            "m".repeat(51),
            new VoucherUpload("v.pdf", PDF));
    assertThatThrownBy(() -> service.registerDirect(longMethod))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("paymentMethod"));
    verifyNoInteractions(guests, files, payments);
  }

  @Test
  void registerDirect_theLargestAmountAndAMethodOfFiftyCharactersAreAccepted() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
    var edge =
        new DirectPayment(
            ADMIN,
            GUEST,
            OCTOBER,
            null,
            new BigDecimal("99999999.99"),
            "m".repeat(50),
            new VoucherUpload("v.pdf", PDF));

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

  @Test
  void registerDirect_aTemporaryGuestHasStaysAndAGuestWithAContractHasPeriods() {
    when(guests.profileOf(GUEST)).thenReturn(guest(false, true));
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("period"));

    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(stays.stayOf(GUEST, STAY)).thenReturn(new StayInfo(STAY));
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

  @Test
  void registerDirect_aGuestWithoutAContractHasNoPeriodToPay() {
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of());

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(InvalidPaymentException.class);
  }

  @Test
  void registerDirect_theFileIsCheckedByItsContentAfterThePeriodAndBeforeTheLivePayment() {
    contractGuestWithOpenContract();
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3};
    byte[] active =
        "%PDF-1.7\n<< /Type /Catalog /OpenAction 5 0 R >>".getBytes(StandardCharsets.US_ASCII);
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
    when(payments.findLiveByStay(STAY))
        .thenReturn(Optional.of(payment(PaymentStatus.APPROVED, null, STAY)));

    assertThatThrownBy(() -> service.registerDirect(forStay(PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
  }

  @Test
  void registerDirect_ifTheInsertFailsTheStoredObjectIsRemoved() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insertApproved(any())).thenThrow(failure);

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
    verify(files).delete(KEY);
  }

  @Test
  void
      registerDirect_ifAnotherRequestGotThePeriodFirstTheRightConflictIsAnsweredAndTheObjectRemoved() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenThrow(new LivePaymentExistsException(PaymentStatus.PENDING))
        .thenThrow(new LivePaymentExistsException(PaymentStatus.APPROVED));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(VoucherAlreadyExistsException.class);
    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentAlreadyExistsForPeriodException.class);
    verify(files, org.mockito.Mockito.times(2)).delete(KEY);
  }

  @Test
  void registerDirect_aCleanupThatFailsDoesNotHideTheFailureThatCausedIt() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    var failure = new IllegalStateException("insert failed");
    when(payments.insertApproved(any())).thenThrow(failure);
    doThrow(new PaymentFileStorageException("down")).when(files).delete(KEY);

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isSameAs(failure);
  }

  @Test
  void registerDirect_ifTheBucketFailsNothingIsInserted() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenThrow(new PaymentFileStorageException("down"));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(PaymentFileUnavailableException.class);
    verify(payments, never()).insertApproved(any());
    verify(files, never()).delete(any());
  }

  @Test
  void registerDirect_aBlankPaymentMethodIsStoredAsNothing() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
    var blank =
        new DirectPayment(
            ADMIN, GUEST, OCTOBER, null, BigDecimal.TEN, "   ", new VoucherUpload("v.pdf", PDF));

    service.registerDirect(blank);

    ArgumentCaptor<DirectDraft> draft = ArgumentCaptor.forClass(DirectDraft.class);
    verify(payments).insertApproved(draft.capture());
    assertThat(draft.getValue().paymentMethod()).isNull();
  }

  @Test
  void registerDirect_anAmountInScientificNotationIsRefused() {
    for (String amount : new String[] {"8E+2", "1e2", "0.8E+3"}) {
      assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", amount, PDF)))
          .as(amount)
          .isInstanceOfSatisfying(
              InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("amount"));
    }
    verifyNoInteractions(guests, files, payments);
  }

  @Test
  void registerDirect_theAmountIsStoredWithTwoDecimals() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any()))
        .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

    service.registerDirect(forPeriod("2026-10", "800", PDF));
    service.registerDirect(forPeriod("2026-10", "800.500", PDF));

    ArgumentCaptor<DirectDraft> draft = ArgumentCaptor.forClass(DirectDraft.class);
    verify(payments, org.mockito.Mockito.times(2)).insertApproved(draft.capture());
    assertThat(draft.getAllValues()).allSatisfy(d -> assertThat(d.amount().scale()).isEqualTo(2));
    assertThat(draft.getAllValues().get(1).amount()).isEqualByComparingTo("800.50");
  }

  @Test
  void registerDirect_ifThePaymentThatBlockedTheInsertIsGoneTheConflictIsNeutral() {
    contractGuestWithOpenContract();
    when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
    when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
    when(payments.insertApproved(any())).thenThrow(new LivePaymentExistsException(null));

    assertThatThrownBy(() -> service.registerDirect(forPeriod("2026-10", "800", PDF)))
        .isInstanceOf(com.tarimatwasi.quipu.payment.port.in.PaymentConcurrentChangeException.class);
    verify(files).delete(KEY);
  }

  @Test
  void list_aPaymentWhoseGuestIsGoneIsLeftOutAndTheRestOfTheQueueStays() {
    Payment orphan =
        new Payment(
            TestIds.next(),
            TestIds.next(),
            CONTRACT,
            null,
            OCTOBER,
            BigDecimal.TEN,
            null,
            PaymentStatus.PENDING,
            null,
            null,
            null);
    PageQuery page = PageQuery.first();
    when(payments.findQueue(null, page))
        .thenReturn(
            new CursorPage<>(
                List.of(stored(orphan), stored(payment(PaymentStatus.PENDING, OCTOBER, null))),
                "next"));
    when(guests.profilesOf(any())).thenReturn(Map.of(GUEST, guest(true, true)));
    when(contracts.environmentsOf(any())).thenReturn(Map.of(CONTRACT, ENVIRONMENT));
    when(environments.codesOf(any())).thenReturn(Map.of(ENVIRONMENT, "201"));

    var queue = service.list(null, page);

    assertThat(queue.content()).hasSize(1);
    assertThat(queue.content().get(0).guestId()).isEqualTo(GUEST);
    assertThat(queue.nextCursor()).isEqualTo("next");
  }

  @Test
  void hiddenCharactersAreRefusedInTheReasonAndInThePaymentMethod() {
    pendingPayment();
    assertThatThrownBy(
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "mal\u202Emotivo")))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    var method =
        new DirectPayment(
            ADMIN,
            GUEST,
            OCTOBER,
            null,
            BigDecimal.TEN,
            "Yape\u200B",
            new VoucherUpload("v.pdf", PDF));
    assertThatThrownBy(() -> service.registerDirect(method))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("paymentMethod"));
  }

  @Test
  void theLimitOfTheReasonCountsCharactersAndNotUtf16Units() {
    String emoji = new String(Character.toChars(0x1F600));
    pendingPayment();

    assertThat(
            service
                .resolve(PAYMENT, new Resolution(ADMIN, Decision.REJECTED, emoji.repeat(500)))
                .rejectionReason())
        .isEqualTo(emoji.repeat(500));
  }
}
