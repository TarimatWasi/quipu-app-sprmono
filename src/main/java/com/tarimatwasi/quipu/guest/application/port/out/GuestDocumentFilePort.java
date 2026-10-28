package com.tarimatwasi.quipu.guest.application.port.out;

import com.tarimatwasi.quipu.shared.file.FileKind;

/** Where the documents of the onboarding live (RN-37), apart from the vouchers and contracts. */
public interface GuestDocumentFilePort {

  /**
   * Stores the file under a new random key and returns the key. The kind is the one deduced from
   * the content, never the one the client declared.
   *
   * @throws GuestDocumentStorageException if the store fails
   */
  String store(byte[] content, FileKind kind);

  /**
   * Removes the object; used to undo a {@link #store} whose row could not be saved.
   *
   * @throws GuestDocumentStorageException if the store fails
   */
  void delete(String key);

  /**
   * A URL that downloads the object for 5 minutes, as an attachment with a fixed name. It is signed
   * on every request and never stored.
   *
   * @throws GuestDocumentStorageException if the store is not configured or fails
   */
  String signedUrl(String key, FileKind kind);
}
