package com.tarimatwasi.quipu.payment.application.service;

import com.tarimatwasi.quipu.payment.application.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.FileKind;
import java.util.Optional;

/**
 * Which file a voucher is, by its bytes: JPEG, PNG or PDF, of at most 5 MB (RF-15). The type the
 * client declares and the extension of its file name are never read. A PDF that names JavaScript, a
 * launch action, an action on open, additional actions or an embedded file is not accepted (see
 * {@link FileKind}).
 */
public final class VoucherFile {

  /** The largest voucher (RF-15, RN-37 sets the same for the documents). */
  public static final int MAX_BYTES = ManagePaymentsUseCase.MAX_VOUCHER_BYTES;

  private VoucherFile() {}

  /** The type of the file, or empty if it is empty, over 5 MB or none of the three kinds. */
  public static Optional<VoucherType> typeOf(byte[] content) {
    if (content.length == 0 || content.length > MAX_BYTES) {
      return Optional.empty();
    }
    return FileKind.of(content).map(kind -> VoucherType.valueOf(kind.name()));
  }
}
