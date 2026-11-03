package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.in.InvalidPaymentException;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Decision;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.DirectPayment;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.GuestKind;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentState;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.Resolution;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.VoucherUpload;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentAlreadyResolvedException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentFileUnavailableException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentNotFoundException;
import com.tarimatwasi.quipu.payment.application.port.in.PaymentReasonRequiredException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentGuestPort.GuestProfile;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
