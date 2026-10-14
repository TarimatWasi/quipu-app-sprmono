package com.tarimatwasi.quipu.payment.domain;

import java.util.Optional;

/**
 * Which file a voucher is, by its bytes: JPEG, PNG or PDF, of at most 5 MB (RF-15). The type the
 * client declares and the extension of its file name are never read.
 */
public final class VoucherFile {

  /** The largest voucher (RF-15, RN-37 sets the same for the documents). */
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};

  private VoucherFile() {}

  /** The type of the file, or empty if it is empty, over 5 MB or none of the three kinds. */
  public static Optional<VoucherType> typeOf(byte[] content) {
    if (content.length == 0 || content.length > MAX_BYTES) {
      return Optional.empty();
    }
    if (startsWith(content, JPEG)) {
      return Optional.of(VoucherType.JPEG);
    }
    if (startsWith(content, PNG)) {
      return Optional.of(VoucherType.PNG);
    }
    if (startsWith(content, PDF)) {
      return Optional.of(VoucherType.PDF);
    }
    return Optional.empty();
  }

  private static boolean startsWith(byte[] content, byte[] magic) {
    if (content.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (content[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
