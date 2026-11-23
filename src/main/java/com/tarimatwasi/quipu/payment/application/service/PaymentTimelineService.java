package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.PaymentTimelineUseCase;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.payment.application.service.ChargeBook.Charge;
import com.tarimatwasi.quipu.payment.domain.PeriodState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs on the outbox delivery of the business day, without a user. It reads the same book as the
 * home of the administrator, so a fact of time never disagrees with what the screens show; it
 * checks states, not dates, so a day the application slept is caught up the next one.
 */
@Service
public class PaymentTimelineService implements PaymentTimelineUseCase {
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;
  private final ChargeBook book;
  private final PaymentTimeFacts facts;

  PaymentTimelineService(
      PaymentRepositoryPort payments,
      PaymentContractPort contracts,
      PaymentStayPort stays,
      PaymentTimeFacts facts) {
    this.contracts = contracts;
    this.stays = stays;
    this.book = new ChargeBook(payments);
    this.facts = facts;
  }

  @Override
  @Transactional
  public void sweep(LocalDate day) {
    List<Charge> current = new ArrayList<>();
    List<Charge> overdue = new ArrayList<>();
    book.readContracts(contracts.allContracts(), day, current, overdue);
    book.readStays(stays.allStays(), day, current, overdue);
    for (Charge charge : current) {
      if (charge.state() == PeriodState.UNPAID && !charge.dueDate().isAfter(day)) {
        facts.due(
            charge.guestId(),
            charge.contractId(),
            charge.stayId(),
            charge.period(),
            charge.amount(),
            charge.dueDate());
      }
    }
    for (Charge charge : overdue) {
      facts.overdue(
          charge.guestId(),
          charge.contractId(),
          charge.stayId(),
          charge.period(),
          charge.amount(),
          charge.dueDate());
    }
  }
}
