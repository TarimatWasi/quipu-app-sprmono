package com.tarimatwasi.quipu.payment.application.port.out;

import java.util.Collection;
import java.util.Map;

/** TAR-230: who resolved a payment, as the administrator's views name it. */
public interface PaymentReviewerPort {

  /** The name of each administrator; an id with no administrator adds nothing. Admin views only. */
  Map<Long, String> namesOf(Collection<Long> administratorIds);
}
