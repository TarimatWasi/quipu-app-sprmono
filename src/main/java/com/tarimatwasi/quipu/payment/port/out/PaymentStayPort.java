package com.tarimatwasi.quipu.payment.port.out;

import com.tarimatwasi.quipu.payment.domain.StayTerms;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public interface PaymentStayPort {

  /** A stay of a temporary guest. */
  record StayInfo(Long id) {}

  /**
   * The stay, only if it belongs to that guest.
   *
   * @throws com.tarimatwasi.quipu.guest.port.in.StayNotFoundException if the guest has no such stay
   */
  StayInfo stayOf(Long guestId, Long stayId);

  /** Every stay of the guest of the session. */
  List<StayTerms> staysOf(Long guestId);

  /** The stay a summary of the guest of the session shows: in course, else next, else last. */
  Optional<StayTerms> relevantStayOf(Long guestId, LocalDate today);

  /** The stay in course on that day of each guest, of every guest, in one read (HU-A04). */
  List<StayTerms> staysInForceOn(LocalDate day);

  /** The ids of the stays of any guest that start in the month (RF-18, D-68). */
  List<Long> idsStartingIn(YearMonth month);
}
