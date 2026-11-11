package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import com.tarimatwasi.quipu.shared.changes.ChangeSignals;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Resolves the functional scope while the business transaction is still open (TAR-372). */
final class PaymentChangeRecorder {
  private final ChangeSignals signals;
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;

  PaymentChangeRecorder(
      ChangeSignals signals, PaymentContractPort contracts, PaymentStayPort stays) {
    this.signals = signals;
    this.contracts = contracts;
    this.stays = stays;
  }

  PaymentView record(PaymentView payment, ChangeSignals.Kind kind) {
    Long contractId = payment.contractId();
    Long stayId = payment.stayId();
    Long environmentId = null;
    if (contractId != null) {
      environmentId = contracts.environmentsOf(Set.of(contractId)).get(contractId);
    } else if (stayId != null) {
      var stay = stays.staysByIds(Set.of(stayId)).get(stayId);
      if (stay != null) {
        environmentId = stay.environmentId();
      }
    }
    record(kind, payment.guestId(), contractId != null, payment.id(), environmentId);
    return payment;
  }

  /** Resolve a guest's scope through its own authorized queries, never the admin batch ports. */
  PaymentView recordOwn(PaymentView payment, ChangeSignals.Kind kind) {
    Long environmentId = null;
    if (payment.contractId() != null) {
      for (var contract : contracts.contractsOf(payment.guestId())) {
        if (contract.id().equals(payment.contractId())) {
          environmentId = contract.environmentId();
          break;
        }
      }
    } else {
      for (var stay : stays.staysOf(payment.guestId())) {
        if (stay.id().equals(payment.stayId())) {
          environmentId = stay.environmentId();
          break;
        }
      }
    }
    record(kind, payment.guestId(), payment.contractId() != null, payment.id(), environmentId);
    return payment;
  }

  void record(
      ChangeSignals.Kind kind,
      Long guestId,
      boolean contractType,
      @Nullable Long paymentId,
      @Nullable Long environmentId) {
    signals.changed(
        new ChangeSignals.Change(kind, guestId, contractType, paymentId, environmentId));
  }
}
