package com.tarimatwasi.quipu.contract.domain;

/**
 * What a contract is on a given day. Only ACTIVE and TERMINATED are stored; EXPIRED is derived from
 * the end date (RN-07).
 */
public enum ContractStatus {
  ACTIVE,
  EXPIRED,
  TERMINATED
}
