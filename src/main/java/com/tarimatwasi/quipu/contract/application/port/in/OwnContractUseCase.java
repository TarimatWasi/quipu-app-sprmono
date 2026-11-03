package com.tarimatwasi.quipu.contract.application.port.in;

import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;
import org.jspecify.annotations.Nullable;

/** CU-18, RN-26: a guest reads its own contracts, whatever their state. */
public interface OwnContractUseCase {

  /**
   * The contracts H2 shows (TAR-237).
   *
   * @param current the contract in force today, or the one that starts first if none is running yet
   *     (RN-07), or the latest one if all ended
   * @param next a renewal already loaded that starts after {@code current} ends (RN-20); null when
   *     there is none
   */
  record OwnContracts(ContractView current, @Nullable ContractView next) {}

  /**
   * The own contracts of the guest, each with the signed URL of its file (null if the storage could
   * not sign it).
   *
   * @throws ContractNotFoundException if the guest never had a contract
   */
  OwnContracts ownContracts(Long guestId);
}
