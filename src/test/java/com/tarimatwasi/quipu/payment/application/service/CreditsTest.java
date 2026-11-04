package com.tarimatwasi.quipu.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.GuestContract;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.ChargedStay;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import com.tarimatwasi.quipu.payment.domain.GuestCredit.UsableOrigin;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.Refund;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import com.tarimatwasi.quipu.support.MutableClock;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BE-SPR-DAT-10 (TAR-279): the credits of several guests are read in four queries, whatever their
 * number, and come out the same as reading each guest on its own.
 */
@ExtendWith(MockitoExtension.class)
class CreditsTest {

  private static final Long GUEST_A = TestIds.next();
  private static final Long GUEST_B = TestIds.next();
  private static final Long OTHER_GUEST = TestIds.next();

  // 2026-10-14 09:00 in Lima
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T14:00:00Z"));

  @Mock PaymentRepositoryPort payments;
  @Mock PaymentContractPort contracts;
  @Mock PaymentStayPort stays;
  @Mock RefundRepositoryPort refunds;

  private Credits credits;

  private final ContractInfo contractA = contract("2026-09-01");
  private final ContractInfo contractB = contract("2026-10-01");
  private final ContractInfo contractOther = contract("2026-09-01");
  private final StayTerms stayA =
      new StayTerms(
          TestIds.next(),
          LocalDate.parse("2026-08-01"),
          LocalDate.parse("2026-08-10"),
          new BigDecimal("100.00"));
  private final StayTerms stayOther =
      new StayTerms(
          TestIds.next(),
          LocalDate.parse("2026-08-01"),
          LocalDate.parse("2026-08-10"),
          new BigDecimal("100.00"));
  // A pays September 50 over the 100 it owes; its stay is paid 30 over and 10 of it was refunded.
  private final Payment septemberA =
      approved(GUEST_A, contractA.id(), null, YearMonth.parse("2026-09"), "150.00");
  private final Payment stayPaymentA = approved(GUEST_A, null, stayA.id(), null, "130.00");
  private final Refund refundA =
      new Refund(
          TestIds.next(),
          GUEST_A,
          stayPaymentA.id(),
          new BigDecimal("10.00"),
          LocalDate.parse("2026-08-20"),
          "Efectivo",
          null,
          Instant.parse("2026-08-20T15:00:00Z"));
  // B pays October exactly.
  private final Payment octoberB =
      approved(GUEST_B, contractB.id(), null, YearMonth.parse("2026-10"), "100.00");

  @BeforeEach
  void setUp() {
    credits = new Credits(payments, contracts, stays, refunds, clock);
  }

  private static ContractInfo contract(String start) {
    return new ContractInfo(
        TestIds.next(),
        TestIds.next(),
        new ContractTerms(LocalDate.parse(start), null, null, 5, 3),
        new BigDecimal("100.00"));
  }

  private static Payment approved(
      Long guestId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      String verified) {
    return new Payment(
        TestIds.next(),
        guestId,
        contractId,
        stayId,
        period,
        new BigDecimal(verified),
        new BigDecimal(verified),
        PaymentStatus.APPROVED,
        null,
        Instant.parse("2026-10-01T15:00:00Z"),
        TestIds.next(),
        Instant.parse("2026-10-01T14:00:00Z"));
  }

  private void everythingInOneRead() {
    when(contracts.allContracts())
        .thenReturn(
            List.of(
                new GuestContract(GUEST_A, contractA),
                new GuestContract(OTHER_GUEST, contractOther),
                new GuestContract(GUEST_B, contractB)));
    when(stays.allStays())
        .thenReturn(
            List.of(
                new ChargedStay(GUEST_A, ref(stayA)),
                new ChargedStay(OTHER_GUEST, ref(stayOther))));
    when(payments.findAllOfGuests(any())).thenReturn(List.of(septemberA, octoberB, stayPaymentA));
    when(refunds.findByGuests(any())).thenReturn(List.of(refundA));
  }

  private static StayRef ref(StayTerms stay) {
    return new StayRef(
        stay.id(), stay.startDate(), stay.endDate(), stay.agreedAmount(), TestIds.next());
  }

  @Test
  void ofGuests_twoGuests_matchesTheLedgerOfEachGuestReadOnItsOwn() {
    everythingInOneRead();
    when(contracts.contractsOf(GUEST_A)).thenReturn(List.of(contractA));
    when(contracts.contractsOf(GUEST_B)).thenReturn(List.of(contractB));
    when(payments.findAllOfGuest(GUEST_A)).thenReturn(List.of(septemberA, stayPaymentA));
    when(payments.findAllOfGuest(GUEST_B)).thenReturn(List.of(octoberB));
    when(stays.staysOfGuest(GUEST_A)).thenReturn(List.of(stayA));
    when(refunds.findByGuest(GUEST_A)).thenReturn(List.of(refundA));
    when(refunds.findByGuest(GUEST_B)).thenReturn(List.of());

    Map<Long, Credits.Ledger> batch = credits.ofGuests(List.of(GUEST_A, GUEST_B));
    Credits.Ledger aloneA = credits.of(GUEST_A, false);
    Credits.Ledger aloneB = credits.of(GUEST_B, false);

    assertThat(batch).containsOnlyKeys(GUEST_A, GUEST_B);
    Credits.Ledger batchA = Objects.requireNonNull(batch.get(GUEST_A));
    Credits.Ledger batchB = Objects.requireNonNull(batch.get(GUEST_B));
    assertThat(batchA.credit()).isEqualTo(aloneA.credit());
    assertThat(batchA.staySurplus()).isEqualTo(aloneA.staySurplus());
    assertThat(batchA.contracts()).isEqualTo(aloneA.contracts());
    assertThat(batchB.credit()).isEqualTo(aloneB.credit());
    // The data is not trivial: A's stay was paid 30 over and keeps its refund, the other guest's
    // contract and stay are left out.
    assertThat(batchA.staySurplus())
        .containsExactly(Map.entry(stayPaymentA.id(), new BigDecimal("30.00")));
    assertThat(batchA.credit().refunds()).containsExactly(refundA);
    assertThat(batchA.stays()).containsOnlyKeys(stayA.id());
    assertThat(batchB.contracts()).containsOnlyKeys(contractB.id());
  }

  @Test
  void ofGuests_severalGuests_readsInFourQueriesAndNeverOneByOne() {
    everythingInOneRead();

    credits.ofGuests(List.of(GUEST_A, GUEST_B, GUEST_A));

    verify(contracts).allContracts();
    verify(stays).allStays();
    verify(payments).findAllOfGuests(Set.of(GUEST_A, GUEST_B));
    verify(refunds).findByGuests(Set.of(GUEST_A, GUEST_B));
    verify(contracts, never()).contractsOf(anyLong());
    verify(payments, never()).findAllOfGuest(anyLong());
    verify(stays, never()).staysOfGuest(anyLong());
    verify(refunds, never()).findByGuest(anyLong());
  }

  @Test
  void ofGuests_noGuests_readsNothing() {
    assertThat(credits.ofGuests(List.of())).isEmpty();

    verifyNoInteractions(payments, contracts, stays, refunds);
  }

  /**
   * TAR-284: the usable credit keeps the payments it was left over from, oldest first, after the
   * periods took it; a contract that ended passes its credit to what is to refund.
   */
  @Test
  void of_theUsableCreditListsItsOriginsAndAnEndedContractPassesItsCreditToTheRefunds() {
    // Ended in September: September is paid 30 over, which is to refund.
    ContractInfo ended =
        new ContractInfo(
            TestIds.next(),
            TestIds.next(),
            new ContractTerms(
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-30"), null, 5, 3),
            new BigDecimal("100.00"));
    Payment augustEnded = approved(GUEST_A, ended.id(), null, YearMonth.parse("2026-08"), "100.00");
    Payment septemberEnded =
        approved(GUEST_A, ended.id(), null, YearMonth.parse("2026-09"), "130.00");
    // Open since September: September leaves 150 and October takes 100 of it, so the 20 paid in
    // October are left over whole.
    Payment septemberOpen =
        approved(GUEST_A, contractA.id(), null, YearMonth.parse("2026-09"), "250.00");
    Payment octoberOpen =
        approved(GUEST_A, contractA.id(), null, YearMonth.parse("2026-10"), "20.00");
    when(contracts.contractsOf(GUEST_A)).thenReturn(List.of(ended, contractA));
    when(payments.findAllOfGuest(GUEST_A))
        .thenReturn(List.of(augustEnded, septemberEnded, septemberOpen, octoberOpen));
    when(refunds.findByGuest(GUEST_A)).thenReturn(List.of());

    var credit = credits.of(GUEST_A, true).credit();

    assertThat(credit.usable()).isEqualByComparingTo("70");
    assertThat(credit.usableOrigins())
        .containsExactly(
            new UsableOrigin(septemberOpen.id(), YearMonth.of(2026, 9), new BigDecimal("50.00")),
            new UsableOrigin(octoberOpen.id(), YearMonth.of(2026, 10), new BigDecimal("20.00")));
    assertThat(credit.usableOrigins())
        .extracting(UsableOrigin::paymentId)
        .doesNotContain(septemberEnded.id());
    assertThat(credit.sources())
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.paymentId()).isEqualTo(septemberEnded.id());
              assertThat(source.amount()).isEqualByComparingTo("30");
            });
    assertThat(credit.balance()).isEqualByComparingTo("100");
    verifyNoInteractions(stays);
  }
}
