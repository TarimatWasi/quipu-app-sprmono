package com.tarimatwasi.quipu.guest.port.in;

/** The guest has a contract in force, so it cannot be deactivated (RN-12). */
public class GuestHasActiveContractException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Long contractId;

  /** Creates the exception for the contract in force of the guest. */
  public GuestHasActiveContractException(Long contractId) {
    super("The guest has a contract in force");
    this.contractId = contractId;
  }

  /** The id of the contract in force. */
  public Long contractId() {
    return contractId;
  }
}
