package com.tarimatwasi.quipu.payment.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentStatus;
import com.tarimatwasi.quipu.payment.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

  private final PaymentJpaRepository jpaRepository;
  private final TransactionTemplate separateTransaction;

  PaymentRepositoryAdapter(
      PaymentJpaRepository jpaRepository, PlatformTransactionManager transactionManager) {
    this.jpaRepository = jpaRepository;
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
    return findByCreation(
        status == null ? null : (root, query, builder) -> builder.equal(root.get("status"), status),
        Sort.Direction.ASC,
        page);
  }

  @Override
  public CursorPage<StoredPayment> findHistory(Long guestId, PageQuery page) {
    // ix_payments_history (guest_id, created_date DESC, id DESC) serves this order.
    return findByCreation(
        (root, query, builder) -> builder.equal(root.get("guestId"), guestId),
        Sort.Direction.DESC,
        page);
  }

  /**
   * One page of the payments that meet the condition, ordered by (createdDate, id) in that
   * direction and read by last index: the page starts right after the key of the cursor.
   */
  private CursorPage<StoredPayment> findByCreation(
      @Nullable Specification<PaymentJpaEntity> condition,
      Sort.Direction direction,
      PageQuery page) {
    CreationKey after = page.after() == null ? null : CreationKey.decode(page.after());
    boolean ascending = direction == Sort.Direction.ASC;
    Specification<PaymentJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (condition != null) {
            where.add(Objects.requireNonNull(condition.toPredicate(root, query, builder)));
          }
          if (after != null) {
            // (createdDate, id) after (date, id) in the order of the list.
            Path<LocalDateTime> createdDate = root.get("createdDate");
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
    return CursorPage.of(rows, page.limit(), PaymentRepositoryAdapter::cursorOf)
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
  public List<Payment> findApprovedOfContracts(Long guestId) {
    return jpaRepository
        .findByGuestIdAndStatusAndContractIdIsNotNull(guestId, PaymentStatus.APPROVED)
        .stream()
        .map(PaymentJpaEntity::toDomain)
        .toList();
  }

  private static String cursorOf(PaymentJpaEntity payment) {
    return CursorToken.encode(
        // PostgreSQL keeps microseconds: a finer value would sort after the row it came from.
        payment.getCreatedDate().orElseThrow().truncatedTo(ChronoUnit.MICROS).toString(),
        Objects.requireNonNull(payment.getId()).toString());
  }

  /** The sort key of a payment, as the cursor carries it. */
  private record CreationKey(LocalDateTime createdDate, Long id) {

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 9999;

    static CreationKey decode(String cursor) {
      List<String> parts = CursorToken.decode(cursor, 2);
      try {
        LocalDateTime createdDate = LocalDateTime.parse(parts.get(0));
        // Java parses years far outside what a PostgreSQL timestamp holds; that would be a 500.
        if (createdDate.getYear() < MIN_YEAR || createdDate.getYear() > MAX_YEAR) {
          throw new InvalidCursorException();
        }
        return new CreationKey(createdDate, Long.valueOf(parts.get(1)));
      } catch (DateTimeParseException | NumberFormatException notAKey) {
        throw new InvalidCursorException();
      }
    }
  }
}
