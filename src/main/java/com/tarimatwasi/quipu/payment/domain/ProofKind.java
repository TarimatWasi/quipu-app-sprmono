package com.tarimatwasi.quipu.payment.domain;

/** What a proof of a payment is (RN-22). */
public enum ProofKind {
  /** An image or a PDF (RF-15). */
  VOUCHER,
  /** An amount in cash, without a voucher. */
  CASH
}
