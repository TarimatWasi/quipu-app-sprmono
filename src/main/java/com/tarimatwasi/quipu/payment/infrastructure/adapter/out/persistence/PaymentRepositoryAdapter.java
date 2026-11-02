package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.application.port.out.LivePaymentExistsException;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import com.tarimatwasi.quipu.payment.domain.Payment;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.PaymentReview;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /**
   * The payments that hold a period or a stay: a rejected one is corrected and sent again, so it
   * holds it too (RN-05, TAR-239). A SUPERSEDED one is history.
   */
  private static final Set<PaymentStatus> LIVE =
      EnumSet.of(PaymentStatus.PENDING, PaymentStatus.APPROVED, PaymentStatus.REJECTED);

  /** The name of the unique indexes of the live payments. */
  private static final String LIVE_INDEX_PREFIX = "uq_payments_live";

  /** How many ids a single read binds at most. */
  private static final int STAY_SLICE = 1000;

  private final PaymentJpaRepository jpaRepository;
  private final PaymentProofJpaRepository proofRepository;
  private final PaymentReviewJpaRepository reviewRepository;
  private final TransactionTemplate separateTransaction;
  private final IdMasker masker;

  PaymentRepositoryAdapter(
      PaymentJpaRepository jpaRepository,
      PaymentProofJpaRepository proofRepository,
      PaymentReviewJpaRepository reviewRepository,
      PlatformTransactionManager transactionManager,
      IdMasker masker) {
    this.jpaRepository = jpaRepository;
    this.proofRepository = proofRepository;
    this.reviewRepository = reviewRepository;
    this.masker = masker;
    this.separateTransaction = new TransactionTemplate(transactionManager);
    this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.separateTransaction.setReadOnly(true);
  }

  @Override
  public StoredPayment insert(NewPayment draft) {
    BigDecimal declared = BigDecimal.ZERO;
    BigDecimal verified = BigDecimal.ZERO;
    for (NewProof proof : draft.proofs()) {
      declared = declared.add(proof.declaredAmount());
      BigDecimal checked = proof.verifiedAmount();
      verified = verified.add(checked == null ? BigDecimal.ZERO : checked);
    }
    boolean approved = draft.status() == PaymentStatus.APPROVED;
    PaymentJpaEntity saved;
    try {
      saved =
          jpaRepository.saveAndFlush(
              new PaymentJpaEntity(draft, declared, approved ? verified : null));
    } catch (DataIntegrityViolationException e) {
      if (!isLiveIndexViolation(e)) {
        throw e;
      }
      throw new LivePaymentExistsException(
          liveStatusOf(draft.contractId(), draft.stayId(), draft.period()));
    }
    Long id = Objects.requireNonNull(saved.getId());
    List<PaymentProofJpaEntity> proofs = new ArrayList<>();
    for (NewProof proof : draft.proofs()) {
      proofs.add(new PaymentProofJpaEntity(id, proof));
    }
    proofRepository.saveAllAndFlush(proofs);
    Instant reviewedAt = draft.reviewedAt();
    Long reviewer = draft.reviewedById();
    if (approved && reviewedAt != null && reviewer != null) {
      addReview(id, new PaymentReview(PaymentStatus.APPROVED, null, reviewedAt, reviewer));
    }
    return new StoredPayment(
        saved.toDomain(),
        proofs.stream().map(PaymentProofJpaEntity::toDomain).toList(),
        saved.createdAt());
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
    return jpaRepository.findById(id).map(this::stored);
  }

  @Override
  public Optional<Long> findContractGuestId(Long id) {
    return jpaRepository.findContractGuestIdById(id);
  }

  @Override
  public Optional<StoredPayment> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(this::stored);
  }

  @Override
  public StoredPayment save(Payment changed) {
    PaymentJpaEntity entity =
        jpaRepository
            .findById(changed.id())
            .orElseThrow(() -> new NoSuchElementException("payment " + changed.id()));
    entity.apply(changed);
    return stored(jpaRepository.saveAndFlush(entity));
  }

  @Override
  public void addReview(Long paymentId, PaymentReview review) {
    reviewRepository.saveAndFlush(new PaymentReviewJpaEntity(paymentId, review));
  }

  @Override
  public List<PaymentReview> findReviews(Long paymentId) {
    return reviewRepository.findByPaymentIdOrderByReviewedAtAscIdAsc(paymentId).stream()
        .map(PaymentReviewJpaEntity::toDomain)
        .toList();
  }

  @Override
  public Map<Long, List<PaymentReview>> findReviewsOf(Collection<Long> paymentIds) {
    List<Long> ids = List.copyOf(paymentIds);
    Map<Long, List<PaymentReview>> reviews = new HashMap<>();
    for (int from = 0; from < ids.size(); from += STAY_SLICE) {
      List<Long> slice = ids.subList(from, Math.min(from + STAY_SLICE, ids.size()));
      for (PaymentReviewJpaEntity row :
          reviewRepository.findByPaymentIdInOrderByReviewedAtAscIdAsc(slice)) {
        reviews.computeIfAbsent(row.paymentId(), key -> new ArrayList<>()).add(row.toDomain());
      }
    }
    return reviews;
  }

  @Override
  public void addProof(Long paymentId, NewProof proof) {
    proofRepository.saveAndFlush(new PaymentProofJpaEntity(paymentId, proof));
  }

  @Override
  public void updateProof(PaymentProof proof) {
    PaymentProofJpaEntity entity =
        proofRepository
            .findById(proof.id())
            .orElseThrow(() -> new NoSuchElementException("proof " + proof.id()));
    entity.apply(proof);
    proofRepository.saveAndFlush(entity);
  }

  @Override
  public void removeProofs(Long paymentId, Collection<Long> proofIds) {
    if (!proofIds.isEmpty()) {
      proofRepository.deleteAllInBatch(proofRepository.findByPaymentIdAndIdIn(paymentId, proofIds));
    }
  }

  @Override
  public CursorPage<StoredPayment> findQueue(@Nullable PaymentStatus status, PageQuery page) {
    // The status is not where the cursor belongs: it changes while the queue is reviewed. A
    // rejection of the past reads as the rejection it was.
    Specification<PaymentJpaEntity> condition =
        status == null
            ? null
            : (root, query, builder) ->
                status == PaymentStatus.REJECTED
                    ? root.get("status").in(PaymentStatus.REJECTED, PaymentStatus.SUPERSEDED)
                    : builder.equal(root.get("status"), status);
    return findByKey(
        condition, "submittedAt", jpaRepository::findSubmittedAt, Sort.Direction.ASC, page);
  }

  @Override
  public CursorPage<StoredPayment> findHistory(Long guestId, PageQuery page) {
    // ix_payments_history (guest_id, created_date DESC, id DESC) serves this order.
    return findByKey(
        (root, query, builder) -> builder.equal(root.get("guestId"), guestId),
        "createdDate",
        id -> jpaRepository.findCreatedDateOfGuest(id, guestId),
        Sort.Direction.DESC,
        page);
  }

  /**
   * One page of the payments that meet the condition, ordered by (key, id) in that direction and
   * read by last index: the page starts right after the payment of the cursor.
   *
   * <p>TAR-227: the cursor carries only the id of that payment, masked in the session that asked,
   * never the internal one nor its date; {@code keyOf} reads the date again, empty when the payment
   * is no longer there or is not one of the listing. Then, as for a cursor of another session or
   * altered, the cursor is invalid.
   */
  private CursorPage<StoredPayment> findByKey(
      @Nullable Specification<PaymentJpaEntity> condition,
      String keyProperty,
      Function<Long, Optional<Instant>> keyOf,
      Sort.Direction direction,
      PageQuery page) {
    SortKey after = page.after() == null ? null : keyOf(page.after(), keyOf);
    boolean ascending = direction == Sort.Direction.ASC;
    Specification<PaymentJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (condition != null) {
            where.add(Objects.requireNonNull(condition.toPredicate(root, query, builder)));
          }
          if (after != null) {
            // (key, id) after (date, id) in the order of the list.
            Path<Instant> key = root.get(keyProperty);
            Path<Long> id = root.get("id");
            where.add(
                builder.or(
                    ascending
                        ? builder.greaterThan(key, after.at())
                        : builder.lessThan(key, after.at()),
                    builder.and(
                        builder.equal(key, after.at()),
                        ascending
                            ? builder.greaterThan(id, after.id())
                            : builder.lessThan(id, after.id()))));
          }
          return builder.and(where.toArray(Predicate[]::new));
        };
    Sort order = Sort.by(new Sort.Order(direction, keyProperty), new Sort.Order(direction, "id"));
    // One more than the limit: the extra row only says that there is a next page.
    List<PaymentJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    CursorPage<PaymentJpaEntity> found =
        CursorPage.of(
            rows,
            page.limit(),
            row -> MaskedCursor.of(masker, IdKind.PAYMENT, Objects.requireNonNull(row.getId())));
    Map<Long, List<PaymentProof>> proofs =
        proofsOf(found.content().stream().map(row -> Objects.requireNonNull(row.getId())).toList());
    return found.map(
        row ->
            new StoredPayment(
                row.toDomain(), proofs.getOrDefault(row.getId(), List.of()), row.createdAt()));
  }

  private StoredPayment stored(PaymentJpaEntity entity) {
    Long id = Objects.requireNonNull(entity.getId());
    return new StoredPayment(
        entity.toDomain(), proofsOf(List.of(id)).getOrDefault(id, List.of()), entity.createdAt());
  }

  /** The proofs of each payment, in the order they were added, in one read per slice of ids. */
  private Map<Long, List<PaymentProof>> proofsOf(List<Long> paymentIds) {
    Map<Long, List<PaymentProof>> proofs = new HashMap<>();
    for (int from = 0; from < paymentIds.size(); from += STAY_SLICE) {
      List<Long> slice = paymentIds.subList(from, Math.min(from + STAY_SLICE, paymentIds.size()));
      for (PaymentProofJpaEntity row : proofRepository.findByPaymentIdInOrderByIdAsc(slice)) {
        proofs.computeIfAbsent(row.paymentId(), key -> new ArrayList<>()).add(row.toDomain());
      }
    }
    return proofs;
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
  public List<Payment> findAllOfGuest(Long guestId) {
    return jpaRepository.findByGuestId(guestId).stream().map(PaymentJpaEntity::toDomain).toList();
  }

  @Override
  public List<Long> guestsWithApprovedPayments() {
    return jpaRepository.findGuestIdsByStatus(PaymentStatus.APPROVED);
  }

  @Override
  public Instant historyPosition(Long guestId, String cursor) {
    return keyOf(cursor, id -> jpaRepository.findCreatedDateOfGuest(id, guestId)).at();
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
  public MonthTotal approvedOfPeriod(YearMonth period) {
    return totalOf(jpaRepository.totalOfPeriod(period.atDay(1), PaymentStatus.APPROVED));
  }

  /** In slices, so that a long list of stays never nears the limit of bound parameters. */
  @Override
  public MonthTotal approvedOfStays(Collection<Long> stayIds) {
    List<Long> ids = List.copyOf(stayIds);
    MonthTotal total = MonthTotal.NONE;
    for (int from = 0; from < ids.size(); from += STAY_SLICE) {
      List<Long> slice = ids.subList(from, Math.min(from + STAY_SLICE, ids.size()));
      total = total.plus(totalOf(jpaRepository.totalOfStays(slice, PaymentStatus.APPROVED)));
    }
    return total;
  }

  private static MonthTotal totalOf(PaymentJpaRepository.SumAndCount read) {
    BigDecimal sum = read.getTotal();
    return new MonthTotal(sum == null ? BigDecimal.ZERO : sum, read.getCount());
  }

  /** Where the cursor stands: the sort key of its payment, read again from the row. */
  private SortKey keyOf(String cursor, Function<Long, Optional<Instant>> keyOf) {
    Long id = MaskedCursor.idOf(masker, IdKind.PAYMENT, cursor);
    Instant at = keyOf.apply(id).orElseThrow(InvalidCursorException::new);
    return new SortKey(at, id);
  }

  /** The sort key of a payment. */
  private record SortKey(Instant at, Long id) {}
}
