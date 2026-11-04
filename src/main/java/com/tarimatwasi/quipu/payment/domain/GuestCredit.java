package com.tarimatwasi.quipu.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * RN-38, RN-39 (TAR-240): the credit of one guest, derived from its contracts, its stays and the
 * refunds it got. A contract keeps its credit for its next periods; once its last period is reached
 * the credit left is to refund, and so is what a stay was paid over its agreed amount (a stay keeps
 * no credit). The refunds are taken from what is to refund oldest first.
 *
 * @param usable the credit the guest still uses in a coming period: what its origins add
 * @param usableOrigins the payments that credit was left over from, oldest first (TAR-284)
 * @param sources what is to refund, oldest first, each with what was already refunded of it
 * @param refunds the refunds of the guest, oldest first
 */
public record GuestCredit(
    BigDecimal usable,
    List<UsableOrigin> usableOrigins,
    List<Source> sources,
    List<Refund> refunds) {

  private static final BigDecimal NONE = BigDecimal.ZERO.setScale(2);

  public GuestCredit {
    usableOrigins = List.copyOf(usableOrigins);
    sources = List.copyOf(sources);
    refunds = List.copyOf(refunds);
    if (sum(usableOrigins).compareTo(usable) != 0) {
      throw new IllegalArgumentException("The usable credit is not what its origins add");
    }
  }

  /**
   * A payment the credit to use was left over from (RN-38, TAR-284): what the periods after it did
   * not take yet. Once the contract reaches its last period it is a {@link Source} instead.
   *
   * @param period the period of that payment
   * @param amount what is left of it, more than 0
   */
  public record UsableOrigin(Long paymentId, YearMonth period, BigDecimal amount) {}

  /**
   * Something to refund: the credit left by a contract that has no period left, or the surplus of a
   * stay.
   *
   * @param paymentId the payment it came from: the last one that left credit in the contract, or
   *     the one of the stay
   * @param contractId set for a contract
   * @param stayId set for a stay
   * @param period the period of that payment; null for a stay
   * @param refunded what the refunds already returned of it
   * @param lastRefund the last refund that returned part of it, if any
   */
  public record Source(
      Long paymentId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      BigDecimal refunded,
      @Nullable Refund lastRefund) {

    /** What is still to refund of it. */
    public BigDecimal pending() {
      return amount.subtract(refunded);
    }

    /** Whether the refunds returned all of it. */
    public boolean refundedWhole() {
      return pending().signum() <= 0;
    }
  }

  /**
   * What a source is before the refunds are taken from it.
   *
   * @param startedOn orders the sources: the start of the contract or of the stay
   */
  public record Origin(
      Long paymentId,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period,
      BigDecimal amount,
      LocalDate startedOn) {}

  /**
   * The credit from what is still to use, what is to refund and the refunds, which are taken oldest
   * first. The usable credit is what its origins add; those with nothing left are dropped.
   */
  public static GuestCredit of(
      List<UsableOrigin> usableOrigins, List<Origin> origins, List<Refund> refunds) {
    List<UsableOrigin> usable =
        usableOrigins.stream()
            .filter(o -> o.amount().signum() > 0)
            .sorted(
                Comparator.comparing(UsableOrigin::period).thenComparing(UsableOrigin::paymentId))
            .toList();
    List<Origin> ordered =
        origins.stream()
            .filter(o -> o.amount().signum() > 0)
            .sorted(Comparator.comparing(Origin::startedOn).thenComparing(Origin::paymentId))
            .toList();
    List<Refund> byDate =
        refunds.stream()
            .sorted(
                Comparator.comparing(Refund::date)
                    .thenComparing(Refund::createdAt)
                    .thenComparing(Refund::id))
            .toList();
    List<Source> sources = new ArrayList<>();
    int next = 0;
    BigDecimal leftOfRefund = byDate.isEmpty() ? BigDecimal.ZERO : byDate.get(0).amount();
    for (Origin origin : ordered) {
      BigDecimal refunded = BigDecimal.ZERO;
      Refund last = null;
      while (next < byDate.size() && refunded.compareTo(origin.amount()) < 0) {
        BigDecimal taken = leftOfRefund.min(origin.amount().subtract(refunded));
        refunded = refunded.add(taken);
        leftOfRefund = leftOfRefund.subtract(taken);
        last = byDate.get(next);
        if (leftOfRefund.signum() == 0) {
          next++;
          leftOfRefund = next < byDate.size() ? byDate.get(next).amount() : BigDecimal.ZERO;
        }
      }
      sources.add(
          new Source(
              origin.paymentId(),
              origin.contractId(),
              origin.stayId(),
              origin.period(),
              origin.amount(),
              refunded,
              last));
    }
    return new GuestCredit(sum(usable), usable, sources, byDate);
  }

  private static BigDecimal sum(List<UsableOrigin> usableOrigins) {
    BigDecimal total = NONE;
    for (UsableOrigin origin : usableOrigins) {
      total = total.add(origin.amount());
    }
    return total;
  }

  /** What is still to refund, after every refund. */
  public BigDecimal pendingRefund() {
    BigDecimal pending = BigDecimal.ZERO;
    for (Source source : sources) {
      pending = pending.add(source.pending());
    }
    return pending;
  }

  /** The credit the guest has now: what it still uses plus what is still to refund. */
  public BigDecimal balance() {
    return usable.add(pendingRefund());
  }

  /** Whether Quipu has something to refund to the guest (A6.5). */
  public boolean toRefund() {
    return pendingRefund().signum() > 0;
  }

  /** The oldest source not refunded whole: where a new refund starts. */
  public @Nullable Source firstPending() {
    return sources.stream().filter(s -> !s.refundedWhole()).findFirst().orElse(null);
  }

  /** The last refund of the guest, if any. */
  public @Nullable Refund lastRefund() {
    return refunds.isEmpty() ? null : refunds.get(refunds.size() - 1);
  }

  /** The source of each payment that is to refund. */
  public Map<Long, Source> sourcesByPayment() {
    Map<Long, Source> byPayment = new HashMap<>();
    sources.forEach(source -> byPayment.put(source.paymentId(), source));
    return byPayment;
  }
}
