package com.tarimatwasi.quipu.payment.application.port.out;

import com.tarimatwasi.quipu.payment.domain.StayTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public interface PaymentStayPort {

  /** A stay of a temporary guest. */
  record StayInfo(Long id) {}

  /**
   * A stay as a payment shows it (A5, TAR-239).
   *
   * @param environmentId {@code null} for a stay registered before TAR-237 («Sin ambiente»)
   */
  record StayRef(
      Long id,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal agreedAmount,
      @Nullable Long environmentId) {}

  /** Those stays, of any guest; an id with no stay adds nothing. Admin views only. */
  Map<Long, StayRef> staysByIds(Collection<Long> stayIds);

  /**
   * The stay, only if it belongs to that guest.
   *
   * @throws com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException if the guest has
   *     no such stay
   */
  StayInfo stayOf(Long guestId, Long stayId);

  /** Every stay of the guest of the session. */
  List<StayTerms> staysOf(Long guestId);

  /** Every stay of the guest, read by the administrator (A5.1, TAR-240). */
  List<StayTerms> staysOfGuest(Long guestId);

  /** The stay a summary of the guest of the session shows: in course, else next, else last. */
  Optional<StayTerms> relevantStayOf(Long guestId, LocalDate today);

  /** The stay in course on that day of each guest, of every guest, in one read (HU-A04). */
  List<StayTerms> staysInForceOn(LocalDate day);

  /** The ids of the stays of any guest that start in the month (RF-18, D-68). */
  List<Long> idsStartingIn(YearMonth month);
}
