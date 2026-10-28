package com.tarimatwasi.quipu.contract.application.port.out;

/** Where the PDF of a contract lives (RF-15). */
public interface ContractFilePort {

  /**
   * Stores the file under a new random key and returns the key.
   *
   * @throws ContractFileStorageException if the store fails
   */
  String store(byte[] content);

  /**
   * Removes the object; used to undo a {@link #store} whose contract could not be saved. Removing a
   * key that is not there is not an error.
   *
   * @throws ContractFileStorageException if the store fails
   */
  void delete(String key);

  /**
   * A URL that reads the object for 15 minutes. It is signed on every request and never stored.
   *
   * @throws ContractFileStorageException if the store is not configured or fails
   */
  String signedUrl(String key);
}
