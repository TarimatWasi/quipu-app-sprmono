package com.tarimatwasi.quipu.contract.application.port.in;

import com.tarimatwasi.quipu.contract.application.port.in.ManageContractsUseCase.ContractView;

/** CU-18, RN-26: a guest reads its own contract, whatever its state. */
public interface OwnContractUseCase {

  /**
   * The latest contract of the guest, with the signed URL of its file.
   *
   * @throws ContractNotFoundException if the guest never had a contract
   */
  ContractView ownContract(Long guestId);
}
