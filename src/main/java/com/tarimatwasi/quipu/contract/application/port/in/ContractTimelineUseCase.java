package com.tarimatwasi.quipu.contract.application.port.in;

import java.time.LocalDate;

/** What a business day makes true about contracts: those about to end (RN-07). */
public interface ContractTimelineUseCase {

  /** States the contracts that end soon; stating them again changes nothing. */
  void sweep(LocalDate day);
}
