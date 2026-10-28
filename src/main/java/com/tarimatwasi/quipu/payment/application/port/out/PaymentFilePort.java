package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.VoucherType;

/** Where the vouchers live (RF-15, RN-22). */
public interface PaymentFilePort {

  /**
   * Stores the file under a new random key and returns the key. The type is the one deduced from
   * the content, never the one the client declared.
   *
   * @throws PaymentFileStorageException if the store fails
   */
  String store(byte[] content, VoucherType type);

  /**
   * Removes the object; used to undo a {@link #store} whose payment could not be saved. Removing a
   * key that is not there is not an error.
   *
   * @throws PaymentFileStorageException if the store fails
   */
  void delete(String key);

  /**
   * A URL that downloads the object for 15 minutes, as an attachment with a fixed name. It is
   * signed on every request and never stored.
   *
   * @throws PaymentFileStorageException if the store is not configured or fails
   */
  String signedUrl(String key, VoucherType type);
}
