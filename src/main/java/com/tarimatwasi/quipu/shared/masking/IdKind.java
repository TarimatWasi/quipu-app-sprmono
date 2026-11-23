package com.tarimatwasi.quipu.shared.masking;

import com.tarimatwasi.quipu.shared.hashing.Sha256;
import java.nio.ByteBuffer;

/** What an id identifies. A masked id only unmasks as the kind it was issued for. */
public enum IdKind {
  USER("user"),
  ENVIRONMENT("environment"),
  EXPENSE("expense"),
  GUEST("guest"),
  STAY("stay"),
  CONTRACT("contract"),
  PAYMENT("payment"),
  GUEST_DOCUMENT("guest-document"),
  GUEST_DOCUMENT_ACTION("guest-document-action"),
  FINE("fine"),
  PAYMENT_PROOF("payment-proof"),
  REFUND("refund"),
  NOTIFICATION("notification");

  private final long tag;

  IdKind(String label) {
    this.tag = tagOf(label);
  }

  /** A fixed 64-bit value that tells this kind from the others inside a masked id. */
  long tag() {
    return tag;
  }

  private static long tagOf(String label) {
    return ByteBuffer.wrap(Sha256.digest("quipu-id:" + label)).getLong();
  }
}
