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
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofType;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.ProofUpload;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyExistsForPeriodException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentConcurrentChangeException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.application.port.in.VoucherAlreadyExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewPayment;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.R2Failures;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PaymentServiceTest extends PaymentServiceFixture {

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
            null,
            CREATED);
    PageQuery page = PageQuery.first();
    when(payments.findQueue(PaymentStatus.PENDING, page))
        .thenReturn(new CursorPage<>(List.of(stored(ofContract), stored(ofStay)), "next"));
    when(guests.profilesOf(any()))
        .thenReturn(
            Map.of(
                GUEST,
                guest(true, true),
                temporaryGuest,
                new GuestProfile(temporaryGuest, null, "87654321", false, true, false, "DNI")));
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
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of(contract("2026-01-01", null, null)));

    var detail = service.get(PAYMENT);

    assertThat(detail.proofs())
        .singleElement()
        .satisfies(proof -> assertThat(proof.fileUrl()).isEqualTo(URL));
    assertThat(detail.expectedAmount()).isEqualByComparingTo("800.00");
    assertThat(detail.amountDue()).isEqualByComparingTo("800.00");
    assertThat(detail.creditApplied()).isEqualByComparingTo("0");
    assertThat(detail.lastPeriod()).isFalse();
    assertThat(detail.payment().status()).isEqualTo(PaymentState.PENDING);
    assertThat(detail.payment().createdAt()).isEqualTo(CREATED);
  }

  @Test
  void get_ofAnUnknownPaymentIsNotFoundAndAFailingBucketIsUnavailable() {
    when(payments.findById(PAYMENT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(PAYMENT)).isInstanceOf(PaymentNotFoundException.class);

    when(payments.findById(PAYMENT))
        .thenReturn(Optional.of(stored(payment(PaymentStatus.APPROVED, OCTOBER, null))));
    when(guests.profileOf(GUEST)).thenReturn(guest(true, true));
    when(contracts.contractsOf(GUEST)).thenReturn(List.of(contract("2026-01-01", null, null)));
    when(files.signedUrl(KEY, VoucherType.PDF)).thenThrow(new PaymentFileStorageException("down"));
    assertThatThrownBy(() -> service.get(PAYMENT))
        .isInstanceOf(PaymentFileUnavailableException.class);
  }

  // ------------------------------------------------------------------ resolving

  private void pendingPayment() {
    when(payments.findByIdForUpdate(PAYMENT))
        .thenReturn(Optional.of(stored(payment(PaymentStatus.PENDING, OCTOBER, null))));
    lenient().doAnswer(call -> stored(call.getArgument(0))).when(payments).save(any());
    // What is due: the monthly amount of the contract of the payment (RN-16).
    lenient()
        .when(contracts.contractsOf(GUEST))
        .thenReturn(List.of(contract("2026-01-01", null, null)));
  }

  @Test
  void resolve_approvesWithTheReviewerOfTheSessionAndTheClock() {
    pendingPayment();

    var view = service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null, VERIFIED));

    assertThat(view.status()).isEqualTo(PaymentState.APPROVED);
    ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
    verify(payments).save(saved.capture());
    assertThat(saved.getValue().reviewedById()).isEqualTo(ADMIN);
    assertThat(saved.getValue().reviewedAt()).isEqualTo(clock.instant());
  }

  @Test
  void resolve_readsTheRowLockedAndIgnoresAReasonGivenWithAnApproval() {
    pendingPayment();

    var view =
        service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, "ignored", VERIFIED));

    assertThat(view.rejectionReason()).isNull();
    verify(payments).findByIdForUpdate(PAYMENT);
    verify(payments, never()).findById(any());
  }

  @Test
  void resolve_rejectsWithTheReasonWithoutItsEdgeSpaces() {
    pendingPayment();

    var view =
        service.resolve(
            PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "  Monto menor  ", VERIFIED));

    assertThat(view.status()).isEqualTo(PaymentState.REJECTED);
    assertThat(view.rejectionReason()).isEqualTo("Monto menor");
  }

  @Test
  void resolve_aRejectionWithoutAReasonIsRefusedWhateverBlankMeans() {
    for (String blank : new String[] {null, "", "   ", " ", " "}) {
      pendingPayment();
      assertThatThrownBy(
              () ->
                  service.resolve(
                      PAYMENT, new Resolution(ADMIN, Decision.REJECTED, blank, VERIFIED)))
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
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, separators, VERIFIED)))
        .isInstanceOf(PaymentReasonRequiredException.class);
  }

  @Test
  void resolve_aReasonOfOnlyATabOrALineBreakIsInvalidNotMissing() {
    for (String reason : new String[] {"\t", "\n", "  \n ", "\u2028"}) {
      pendingPayment();
      assertThatThrownBy(
              () ->
                  service.resolve(
                      PAYMENT, new Resolution(ADMIN, Decision.REJECTED, reason, VERIFIED)))
          .isInstanceOfSatisfying(
              InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    }
  }

  @Test
  void resolve_aReasonOverFiveHundredCharactersOrWithControlCharactersIsInvalid() {
    pendingPayment();

    assertThat(
            service
                .resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "x".repeat(500), VERIFIED))
                .rejectionReason())
        .hasSize(500);
    pendingPayment();
    assertThatThrownBy(
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "x".repeat(501), VERIFIED)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    assertThatThrownBy(
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "mal\u0000motivo", VERIFIED)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    assertThatThrownBy(
            () ->
                service.resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "esc\u001Bape", VERIFIED)))
        .isInstanceOf(InvalidPaymentException.class);
  }

  @Test
  void resolve_aPaymentThatIsNoLongerPendingIsAlreadyResolved() {
    // TAR-239: a rejected payment can still be decided on; an approved one or a past rejection not.
    for (PaymentStatus resolved :
        new PaymentStatus[] {PaymentStatus.APPROVED, PaymentStatus.SUPERSEDED}) {
      when(payments.findByIdForUpdate(PAYMENT))
          .thenReturn(Optional.of(stored(payment(resolved, OCTOBER, null))));

      assertThatThrownBy(
              () ->
                  service.resolve(
                      PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null, VERIFIED)))
          .isInstanceOf(PaymentAlreadyResolvedException.class);
      assertThatThrownBy(
              () ->
                  service.resolve(
                      PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "Tarde", VERIFIED)))
          .isInstanceOf(PaymentAlreadyResolvedException.class);
    }
    verify(payments, never()).save(any());
  }

  @Test
  void resolve_ofAnUnknownPaymentIsNotFound() {
    when(payments.findByIdForUpdate(PAYMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.resolve(PAYMENT, new Resolution(ADMIN, Decision.APPROVED, null, VERIFIED)))
        .isInstanceOf(PaymentNotFoundException.class);
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
            null,
            CREATED);
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
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, "mal\u202Emotivo", VERIFIED)))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class, e -> assertThat(e.field()).isEqualTo("reason"));
    var method =
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
                    BigDecimal.TEN,
                    "Yape\u200B",
                    new VoucherUpload("v.pdf", PDF))));
    assertThatThrownBy(() -> service.registerDirect(method))
        .isInstanceOfSatisfying(
            InvalidPaymentException.class,
            e -> assertThat(e.field()).isEqualTo("proofs[0].method"));
  }

  @Test
  void theLimitOfTheReasonCountsCharactersAndNotUtf16Units() {
    String emoji = new String(Character.toChars(0x1F600));
    pendingPayment();

    assertThat(
            service
                .resolve(
                    PAYMENT, new Resolution(ADMIN, Decision.REJECTED, emoji.repeat(500), VERIFIED))
                .rejectionReason())
        .isEqualTo(emoji.repeat(500));
  }

  /** A5.1, CU-14, CU-28: the payment the administrator registers by hand (TAR-239). */
  @Nested
  class PaymentByHand {

    // ------------------------------------------------------------------ the payment by hand

    @Test
    void registerDirect_ofAPeriodStoresTheVoucherAndInsertsItApprovedByTheAdmin() {
      contractGuestWithOpenContract();
      when(payments.findLiveByPeriod(CONTRACT, OCTOBER)).thenReturn(Optional.empty());
      when(files.store(PDF, VoucherType.PDF)).thenReturn(KEY);
      when(payments.insert(any()))
          .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

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
      when(payments.insert(any()))
          .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
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
      when(payments.insert(any()))
          .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));
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
      when(payments.insert(any()))
          .thenReturn(stored(payment(PaymentStatus.APPROVED, OCTOBER, null)));

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
}
