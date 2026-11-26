package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.ContractInfo;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort.GuestContract;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.ChargedStay;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort.StayRef;
import com.tarimatwasi.quipu.payment.domain.CreditLedger;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import com.tarimatwasi.quipu.payment.domain.StayPayments;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The charges of every contract and stay on a day, derived by the same terms as the account status
 * (RN-06) with the credit read in (RN-38): the home of the administrator and the facts of time read
 * the same book. One read of payments per list, never one per contract or stay.
 */
final class ChargeBook {
  private final PaymentRepositoryPort payments;

  ChargeBook(PaymentRepositoryPort payments) {
    this.payments = payments;
  }

  /** What is charged for a period or a stay, with its state on the day read. */
  record Charge(
      Long guestId,
      boolean contractType,
      @Nullable Long environmentId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      @Nullable LocalDate stayStartDate,
      @Nullable LocalDate stayEndDate,
      BigDecimal amount,
      LocalDate dueDate,
      PeriodState state,
      @Nullable Long paymentId) {}

  /** The period of the current month of each active contract, and every overdue period. */
  void readContracts(
      List<GuestContract> all, LocalDate today, List<Charge> ofToday, List<Charge> overdue) {
    if (all.isEmpty()) {
      return;
    }
    Map<Long, List<Payment>> byContract =
        payments.findAllByContracts(all.stream().map(c -> c.contract().id()).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.contractId())));
    YearMonth month = YearMonth.from(today);
    for (GuestContract owned : all) {
      ContractInfo contract = owned.contract();
      CreditLedger ledger =
          CreditLedger.of(
              contract.terms(),
              contract.monthlyAmount(),
              byContract.getOrDefault(contract.id(), List.of()),
              today);
      for (CreditLedger.Line line : ledger.lines()) {
        Charge charge =
            new Charge(
                owned.guestId(),
                true,
                contract.environmentId(),
                contract.id(),
                null,
                line.period(),
                null,
                null,
                line.amountDue(),
                line.status().dueDate(),
                line.status().state(),
                line.status().paymentId());
        if (line.period().equals(month) && contract.terms().activeOn(today)) {
          ofToday.add(charge);
        }
        if (line.status().state() == PeriodState.OVERDUE) {
          overdue.add(charge);
        }
      }
    }
  }

  /** The stay in force today of each guest, and every overdue stay. */
  void readStays(
      List<ChargedStay> all, LocalDate today, List<Charge> ofToday, List<Charge> overdue) {
    if (all.isEmpty()) {
      return;
    }
    Map<Long, List<Payment>> byStay =
        payments.findAllByStays(all.stream().map(s -> s.stay().id()).toList()).stream()
            .collect(Collectors.groupingBy(p -> Objects.requireNonNull(p.stayId())));
    for (ChargedStay owned : all) {
      StayRef stay = owned.stay();
      StayPayments.StayStatus status =
          StayPayments.status(stay.endDate(), byStay.getOrDefault(stay.id(), List.of()), today);
      Charge charge =
          new Charge(
              owned.guestId(),
              false,
              stay.environmentId(),
              null,
              stay.id(),
              null,
              stay.startDate(),
              stay.endDate(),
              stay.agreedAmount(),
              stay.endDate(),
              status.state(),
              status.paymentId());
      if (!stay.startDate().isAfter(today) && !stay.endDate().isBefore(today)) {
        ofToday.add(charge);
      }
      if (status.state() == PeriodState.OVERDUE) {
        overdue.add(charge);
      }
    }
  }
}
