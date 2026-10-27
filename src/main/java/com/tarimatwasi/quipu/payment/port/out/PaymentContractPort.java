package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.ContractTerms;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface PaymentContractPort {

  /** A contract with the terms the payments are counted from and the amount of each month. */
  record ContractInfo(Long id, Long environmentId, ContractTerms terms, BigDecimal monthlyAmount) {}

  /** Every contract of the guest, whatever its state, the oldest first. */
  List<ContractInfo> contractsOf(Long guestId);

  /**
   * Like {@link #contractsOf} but the rows stay locked until the transaction ends (TAR-192), so a
   * termination that races the read is seen or waits. Needs a transaction.
   */
  List<ContractInfo> lockedContractsOf(Long guestId);

  /** The environment of each contract; an id with no contract adds nothing. */
  Map<Long, Long> environmentsOf(Collection<Long> contractIds);

  /** The current (ACTIVE) contract of the environment, if it has one (RN-07). */
  List<ContractInfo> currentContractsOf(Long environmentId);
}
