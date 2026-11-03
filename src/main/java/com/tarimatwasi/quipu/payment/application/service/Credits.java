package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.SurplusOutcome;
import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.SurplusView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.BusinessDay;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.GuestCredit;
import com.tarimatwasi.quipu.payment.domain.GuestCredit.Origin;
import com.tarimatwasi.quipu.payment.domain.GuestCredit.Source;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.domain.StayTerms;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * RN-38, RN-39 (TAR-240): the credit of a guest, derived on every read from its contracts, its
 * stays, the payments of both and its refunds; no balance is stored. A guest has few contracts and
 * payments, so it is read whole. Who may read it is checked by the caller.
 */
final class Credits {

  private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

  private final PaymentRepositoryPort payments;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final RefundRepositoryPort refunds;
  private final Clock clock;

  Credits(
      PaymentRepositoryPort payments,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      RefundRepositoryPort refunds,
      Clock clock) {
    this.payments = payments;
    this.contracts = contracts;
    this.stays = stays;
    this.refunds = refunds;
    this.clock = clock;
  }

  /**
   * The credit of the guest.
   *
   * @param own whether the guest reads its own credit; the administrator reads any guest's stays
   */
  Ledger of(Long guestId, boolean own) {
    return of(guestId, contracts.contractsOf(guestId), own);
  }

  /** Like {@link #of(Long, boolean)} with the contracts already read (or locked) by the caller. */
  Ledger of(Long guestId, List<ContractInfo> owned, boolean own) {
    LocalDate today = BusinessDay.today(clock);
    List<Payment> all = payments.findAllOfGuest(guestId);
    Map<Long, ContractInfo> byId = new HashMap<>();
    Map<Long, CreditLedger> ledgers = new HashMap<>();
    List<Origin> origins = new ArrayList<>();
    BigDecimal usable = NONE;
    for (ContractInfo contract : owned) {
      List<Payment> ofContract =
          all.stream().filter(p -> contract.id().equals(p.contractId())).toList();
      CreditLedger ledger =
          CreditLedger.of(contract.terms(), contract.monthlyAmount(), ofContract, today);
      byId.put(contract.id(), contract);
      ledgers.put(contract.id(), ledger);
      usable = usable.add(ledger.usable());
      Long from = ledger.carryPaymentId();
      if (ledger.refundable().signum() > 0 && from != null) {
        YearMonth period =
            ofContract.stream()
                .filter(p -> p.id().equals(from))
                .map(Payment::period)
                .findFirst()
                .orElse(null);
        origins.add(
            new Origin(
                from,
                contract.id(),
                null,
                period,
                ledger.refundable(),
                contract.terms().startDate()));
      }
    }
    Map<Long, StayTerms> stayTerms = new HashMap<>();
    if (all.stream().anyMatch(p -> p.stayId() != null)) {
      (own ? stays.staysOf(guestId) : stays.staysOfGuest(guestId))
          .forEach(stay -> stayTerms.put(stay.id(), stay));
    }
    Map<Long, BigDecimal> staySurplus = staySurplus(all, stayTerms, origins);
    GuestCredit credit = GuestCredit.of(usable, origins, refunds.findByGuest(guestId));
    return new Ledger(byId, ledgers, stayTerms, staySurplus, credit);
  }

  /**
   * What each approved stay payment was paid over the agreed amount: a stay keeps no credit, so it
   * is refunded (RN-38, T1); each one is added to the origins of what is to refund.
   */
  private static Map<Long, BigDecimal> staySurplus(
      List<Payment> all, Map<Long, StayTerms> stayTerms, List<Origin> origins) {
    Map<Long, BigDecimal> staySurplus = new HashMap<>();
    for (Payment payment : all) {
      Long stayId = payment.stayId();
      BigDecimal verified = payment.verifiedAmount();
      StayTerms stay = stayId == null ? null : stayTerms.get(stayId);
      if (stay == null || verified == null || payment.status() != PaymentStatus.APPROVED) {
        continue;
      }
      BigDecimal over = cents(verified).subtract(cents(stay.agreedAmount()));
      if (over.signum() > 0) {
        staySurplus.put(payment.id(), over);
        origins.add(new Origin(payment.id(), null, stayId, null, over, stay.startDate()));
      }
    }
    return staySurplus;
  }

  private static BigDecimal cents(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.UNNECESSARY);
  }

  /**
   * The credit of one guest as it was read.
   *
   * @param staySurplus what each approved stay payment was paid over the agreed amount
   */
  record Ledger(
      Map<Long, ContractInfo> contracts,
      Map<Long, CreditLedger> ledgers,
      Map<Long, StayTerms> stays,
      Map<Long, BigDecimal> staySurplus,
      GuestCredit credit) {

    /** The period of a contract as the ledger read it; null if the contract does not charge it. */
    CreditLedger.@Nullable Line line(@Nullable Long contractId, @Nullable YearMonth period) {
      CreditLedger ledger = contractId == null ? null : ledgers.get(contractId);
      return ledger == null || period == null ? null : ledger.line(period);
    }

    /**
     * The credit a period takes: the one of its line, else (a month not charged yet) the credit the
     * contract has left; 0 for an unknown contract.
     */
    BigDecimal creditFor(Long contractId, YearMonth period, BigDecimal monthlyAmount) {
      CreditLedger ledger = ledgers.get(contractId);
      return ledger == null ? NONE : ledger.creditFor(period, monthlyAmount);
    }

    /**
     * What an approved payment left over (RN-38, RN-39): of a stay, always refunded; of the last
     * period of a contract, refunded; of any other period, credit for the next one. Null when it
     * left nothing.
     */
    @Nullable SurplusView surplusOf(Payment payment) {
      if (payment.status() != PaymentStatus.APPROVED) {
        return null;
      }
      Map<Long, Source> sources = credit.sourcesByPayment();
      BigDecimal ofStay = staySurplus.get(payment.id());
      if (ofStay != null) {
        return new SurplusView(ofStay, refundOutcome(sources.get(payment.id())));
      }
      CreditLedger.Line line = line(payment.contractId(), payment.period());
      if (line == null
          || !payment.id().equals(line.approvedPaymentId())
          || line.surplus().signum() <= 0) {
        return null;
      }
      return new SurplusView(
          line.surplus(),
          line.last() ? refundOutcome(sources.get(payment.id())) : SurplusOutcome.CREDIT);
    }

    /** The refund state of what a stay or a stay-like surplus left over. */
    @Nullable Source sourceOf(Long paymentId) {
      return credit.sourcesByPayment().get(paymentId);
    }

    private static SurplusOutcome refundOutcome(@Nullable Source source) {
      return source != null && source.refundedWhole()
          ? SurplusOutcome.REFUNDED
          : SurplusOutcome.REFUND_PENDING;
    }
  }
}
