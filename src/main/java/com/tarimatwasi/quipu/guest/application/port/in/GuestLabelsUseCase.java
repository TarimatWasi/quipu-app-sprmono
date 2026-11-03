package com.tarimatwasi.quipu.guest.application.port.in;

import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * TAR-237 (A4, H2): who a guest is on a contract, for the administrator and for the guest reading
 * its own contract. The caller decides which ids it may show.
 */
public interface GuestLabelsUseCase {

  /**
   * The name and the document of a guest.
   *
   * @param name null while the guest has not given it in the onboarding
   * @param documentType DNI, CE, PASSPORT or OTHER
   */
  record Label(@Nullable String name, String documentType, String documentNumber) {}

  /** The label of each guest, whatever its state; an id with no guest adds nothing. */
  Map<Long, Label> labelsOf(Collection<Long> ids);
}
