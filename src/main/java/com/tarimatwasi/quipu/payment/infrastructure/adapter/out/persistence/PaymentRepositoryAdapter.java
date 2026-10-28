package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.MaskedCursor;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PaymentRepositoryAdapter implements PaymentRepositoryPort {

  /** The payments that count for a period or a stay: a rejected one leaves it open (RN-05). */
  private static final Set<PaymentStatus> LIVE =
      EnumSet.of(PaymentStatus.PENDING, PaymentStatus.APPROVED);

  /** The name of the unique indexes of the live payments (V13). */
  private static final String LIVE_INDEX_PREFIX = "uq_payments_live";

  /** How many ids a single read binds at most. */
  private static final int STAY_SLICE = 1000;

  private final PaymentJpaRepository jpaRepository;
  private final TransactionTemplate separateTransaction;
  private final IdMasker masker;

  PaymentRepositoryAdapter(
      PaymentJpaRepository jpaRepository,
      PlatformTransactionManager transactionManager,
      IdMasker masker) {
    this.jpaRepository = jpaRepository;
    this.masker = masker;
    this.separateTransaction = new TransactionTemplate(transactionManager);
    this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.separateTransaction.setReadOnly(true);
  }

  @Override
  public StoredPayment insertPending(VoucherDraft draft) {
    return insert(new PaymentJpaEntity(draft), draft.contractId(), draft.stayId(), draft.period());
  }

  @Override
  public StoredPayment insertApproved(DirectDraft draft) {
    return insert(new PaymentJpaEntity(draft), draft.contractId(), draft.stayId(), draft.period());
  }

  private StoredPayment insert(
      PaymentJpaEntity entity,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable YearMonth period) {
    try {
      return jpaRepository.saveAndFlush(entity).toStored();
    } catch (DataIntegrityViolationException e) {
      if (!isLiveIndexViolation(e)) {
        throw e;
      }
      throw new LivePaymentExistsException(liveStatusOf(contractId, stayId, period));
    }
  }

  /** Which of the two unique indexes of the live payments refused the row. */
  private static boolean isLiveIndexViolation(DataIntegrityViolationException e) {
    String message = e.getMostSpecificCause().getMessage();
    return message != null && message.contains(LIVE_INDEX_PREFIX);
  }

  /**
   * The failed insert left its transaction unusable, so the payment that is already there is read
   * in a transaction of its own: it was committed by the other request, or there would be no
   * violation.
   */
  private @Nullable PaymentStatus liveStatusOf(
      @Nullable Long contractId, @Nullable Long stayId, @Nullable YearMonth period) {
    return separateTransaction.execute(
        status ->
            (stayId != null
                    ? findLiveByStay(stayId)
                    : findLiveByPeriod(
                        Objects.requireNonNull(contractId), Objects.requireNonNull(period)))
                .map(Payment::status)
                .orElse(null));
  }

  @Override
  public Optional<StoredPayment> findById(Long id) {
    return jpaRepository.findById(id).map(PaymentJpaEntity::toStored);
  }

  @Override
  public Optional<Long> findContractGuestId(Long id) {
    return jpaRepository.findContractGuestIdById(id);
  }

  @Override
  public Optional<StoredPayment> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(PaymentJpaEntity::toStored);
  }

  @Override
  public StoredPayment save(Payment resolved) {
    PaymentJpaEntity entity =
        jpaRepository
            .findById(resolved.id())
            .orElseThrow(() -> new NoSuchElementException("payment " + resolved.id()));
    entity.apply(resolved);
    return jpaRepository.saveAndFlush(entity).toStored();
  }

  @Override
  public CursorPage<StoredPayment> findQueue(@Nullable PaymentStatus status, PageQuery page) {
    // The status is not where the cursor belongs: it changes while the queue is reviewed.
    return findByCreation(
        status == null ? null : (root, query, builder) -> builder.equal(root.get("status"), status),
        jpaRepository::findCreatedDate,
        Sort.Direction.ASC,
        page);
  }

  @Override
  public CursorPage<StoredPayment> findHistory(Long guestId, PageQuery page) {
    // ix_payments_history (guest_id, created_date DESC, id DESC) serves this order.
    return findByCreation(
        (root, query, builder) -> builder.equal(root.get("guestId"), guestId),
        id -> jpaRepository.findCreatedDateOfGuest(id, guestId),
        Sort.Direction.DESC,
        page);
  }

  /**
   * One page of the payments that meet the condition, ordered by (createdDate, id) in that
   * direction and read by last index: the page starts right after the payment of the cursor.
   *
   * <p>TAR-227: the cursor carries only the id of that payment, masked in the session that asked,
   * never the internal one nor its date; {@code createdDateOf} reads the date again, empty when the
   * payment is no longer there or is not one of the listing. Then, as for a cursor of another
   * session or altered, the cursor is invalid.
   */
  private CursorPage<StoredPayment> findByCreation(
      @Nullable Specification<PaymentJpaEntity> condition,
      Function<Long, Optional<Instant>> createdDateOf,
      Sort.Direction direction,
      PageQuery page) {
    CreationKey after = page.after() == null ? null : keyOf(page.after(), createdDateOf);
    boolean ascending = direction == Sort.Direction.ASC;
    Specification<PaymentJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (condition != null) {
            where.add(Objects.requireNonNull(condition.toPredicate(root, query, builder)));
          }
          if (after != null) {
            // (createdDate, id) after (date, id) in the order of the list.
            Path<Instant> createdDate = root.get("createdDate");
            Path<Long> id = root.get("id");
            where.add(
                builder.or(
                    ascending
                        ? builder.greaterThan(createdDate, after.createdDate())
                        : builder.lessThan(createdDate, after.createdDate()),
                    builder.and(
                        builder.equal(createdDate, after.createdDate()),
                        ascending
                            ? builder.greaterThan(id, after.id())
                            : builder.lessThan(id, after.id()))));
          }
          return builder.and(where.toArray(Predicate[]::new));
        };
    Sort order = Sort.by(new Sort.Order(direction, "createdDate"), new Sort.Order(direction, "id"));
    // One more than the limit: the extra row only says that there is a next page.
    List<PaymentJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(
            rows,
            page.limit(),
            row -> MaskedCursor.of(masker, IdKind.PAYMENT, Objects.requireNonNull(row.getId())))
        .map(PaymentJpaEntity::toStored);
  }

  @Override
  public Optional<Payment> findLiveByPeriod(Long contractId, YearMonth period) {
    return jpaRepository
        .findFirstByContractIdAndPeriodAndStatusIn(contractId, period.atDay(1), LIVE)
        .map(PaymentJpaEntity::toDomain);
  }

  @Override
  public Optional<Payment> findLiveByStay(Long stayId) {
    return jpaRepository.findFirstByStayIdAndStatusIn(stayId, LIVE).map(PaymentJpaEntity::toDomain);
  }

  @Override
  public List<Payment> findAllByContract(Long contractId) {
    return jpaRepository.findByContractId(contractId).stream()
        .map(PaymentJpaEntity::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findAllByStay(Long stayId) {
    return jpaRepository.findByStayId(stayId).stream().map(PaymentJpaEntity::toDomain).toList();
  }

  @Override
  public List<Payment> findAllByContractsAndPeriod(Collection<Long> contractIds, YearMonth period) {
    LocalDate month = period.atDay(1);
    return inSlices(contractIds, slice -> jpaRepository.findByContractIdInAndPeriod(slice, month));
  }

  @Override
  public List<Payment> findAllByStays(Collection<Long> stayIds) {
    return inSlices(stayIds, jpaRepository::findByStayIdIn);
  }

  /** One read per slice of ids, so that a long list never nears the limit of bound parameters. */
  private static List<Payment> inSlices(
      Collection<Long> ids, Function<List<Long>, List<PaymentJpaEntity>> read) {
    List<Long> all = List.copyOf(ids);
    List<Payment> found = new ArrayList<>();
    for (int from = 0; from < all.size(); from += STAY_SLICE) {
      read.apply(all.subList(from, Math.min(from + STAY_SLICE, all.size())))
          .forEach(row -> found.add(row.toDomain()));
    }
    return found;
  }

  @Override
  public long countByStatus(PaymentStatus status) {
    return jpaRepository.countByStatus(status);
  }

  @Override
  public List<Payment> findApprovedOfContracts(Long guestId) {
    return jpaRepository
        .findByGuestIdAndStatusAndContractIdIsNotNull(guestId, PaymentStatus.APPROVED)
        .stream()
        .map(PaymentJpaEntity::toDomain)
        .toList();
  }

  @Override
  public BigDecimal sumApprovedOfPeriod(YearMonth period) {
    return orZero(jpaRepository.sumAmountOfPeriod(period.atDay(1), PaymentStatus.APPROVED));
  }

  /** In slices, so that a long list of stays never nears the limit of bound parameters. */
  @Override
  public BigDecimal sumApprovedOfStays(Collection<Long> stayIds) {
    List<Long> ids = List.copyOf(stayIds);
    BigDecimal total = BigDecimal.ZERO;
    for (int from = 0; from < ids.size(); from += STAY_SLICE) {
      List<Long> slice = ids.subList(from, Math.min(from + STAY_SLICE, ids.size()));
      total = total.add(orZero(jpaRepository.sumAmountOfStays(slice, PaymentStatus.APPROVED)));
    }
    return total;
  }

  private static BigDecimal orZero(@Nullable BigDecimal sum) {
    return sum == null ? BigDecimal.ZERO : sum;
  }

  /** Where the cursor stands: the sort key of its payment, read again from the row. */
  private CreationKey keyOf(String cursor, Function<Long, Optional<Instant>> createdDateOf) {
    Long id = MaskedCursor.idOf(masker, IdKind.PAYMENT, cursor);
    Instant createdDate = createdDateOf.apply(id).orElseThrow(InvalidCursorException::new);
    return new CreationKey(createdDate, id);
  }

  /** The sort key of a payment. */
  private record CreationKey(Instant createdDate, Long id) {}
}
