package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface PaymentContractPort {

  /** A contract with the terms the payments are counted from. */
  record ContractInfo(Long id, Long environmentId, ContractTerms terms) {}

  /** Every contract of the guest, whatever its state, the oldest first. */
  List<ContractInfo> contractsOf(Long guestId);

  /** The environment of each contract; an id with no contract adds nothing. */
  Map<Long, Long> environmentsOf(Collection<Long> contractIds);
}
