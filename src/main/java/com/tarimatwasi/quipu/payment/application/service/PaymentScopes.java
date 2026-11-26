package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase.PaymentView;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentContractPort;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentStayPort;
import java.util.Set;

/**
 * Resolves, while the business transaction is still open, where the guest lives for what a payment
 * pays: a fact its events carry, so no listener queries after the commit (TAR-372).
 */
final class PaymentScopes {
  private final PaymentContractPort contracts;
  private final PaymentStayPort stays;

  PaymentScopes(PaymentContractPort contracts, PaymentStayPort stays) {
    this.contracts = contracts;
    this.stays = stays;
  }

  /** For an administrator: the batch ports of the administration. */
  PaymentView scoped(PaymentView payment) {
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
    return payment.inEnvironment(environmentId);
  }

  /** For the guest itself: its own authorized queries, never the admin batch ports. */
  PaymentView ownScoped(PaymentView payment) {
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
    return payment.inEnvironment(environmentId);
  }
}
