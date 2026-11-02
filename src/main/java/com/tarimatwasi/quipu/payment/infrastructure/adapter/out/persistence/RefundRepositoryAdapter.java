package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.application.port.out.RefundRepositoryPort;
import com.tarimatwasi.quipu.payment.domain.MonthTotal;
import com.tarimatwasi.quipu.payment.domain.Refund;
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
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class RefundRepositoryAdapter implements RefundRepositoryPort {

  private final RefundJpaRepository jpaRepository;
  private final IdMasker masker;

  RefundRepositoryAdapter(RefundJpaRepository jpaRepository, IdMasker masker) {
    this.jpaRepository = jpaRepository;
    this.masker = masker;
  }

  @Override
  public Refund insert(
      Long guestId,
      Long fromPaymentId,
      BigDecimal amount,
      LocalDate date,
      String method,
      @Nullable String note) {
    return jpaRepository
        .saveAndFlush(new RefundJpaEntity(guestId, fromPaymentId, amount, date, method, note))
        .toDomain();
  }

  @Override
  public List<Refund> findByGuest(Long guestId) {
    return jpaRepository.findByGuestId(guestId).stream().map(RefundJpaEntity::toDomain).toList();
  }

  /**
   * Newest first by (date, creation, id). TAR-227: the cursor carries only the masked id of the
   * last refund; its date and creation are read again from the row, and a refund that is not of the
   * month makes the cursor invalid.
   */
  @Override
  public CursorPage<Refund> findByMonth(YearMonth month, PageQuery page) {
    LocalDate from = month.atDay(1);
    LocalDate to = month.plusMonths(1).atDay(1);
    Refund after = page.after() == null ? null : refundAt(page.after(), from, to);
    Specification<RefundJpaEntity> filter =
        (root, query, builder) -> {
          Path<LocalDate> date = root.get("refundedOn");
          Predicate inMonth =
              builder.and(builder.greaterThanOrEqualTo(date, from), builder.lessThan(date, to));
          if (after == null) {
            return inMonth;
          }
          Path<Instant> created = root.get("createdDate");
          Path<Long> id = root.get("id");
          // (date, created, id) < (after's), in the order of the listing.
          return builder.and(
              inMonth,
              builder.or(
                  builder.lessThan(date, after.date()),
                  builder.and(
                      builder.equal(date, after.date()),
                      builder.or(
                          builder.lessThan(created, after.createdAt()),
                          builder.and(
                              builder.equal(created, after.createdAt()),
                              builder.lessThan(id, after.id()))))));
        };
    Sort order = Sort.by(Sort.Direction.DESC, "refundedOn", "createdDate", "id");
    List<RefundJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(
            rows,
            page.limit(),
            row -> MaskedCursor.of(masker, IdKind.REFUND, Objects.requireNonNull(row.getId())))
        .map(RefundJpaEntity::toDomain);
  }

  @Override
  public MonthTotal totalOfMonth(YearMonth month) {
    PaymentJpaRepository.SumAndCount read =
        jpaRepository.totalBetween(month.atDay(1), month.plusMonths(1).atDay(1));
    BigDecimal sum = read.getTotal();
    return new MonthTotal(sum == null ? BigDecimal.ZERO : sum, read.getCount());
  }

  private Refund refundAt(String cursor, LocalDate from, LocalDate to) {
    Long id = MaskedCursor.idOf(masker, IdKind.REFUND, cursor);
    return jpaRepository
        .findInMonth(id, from, to)
        .map(RefundJpaEntity::toDomain)
        .orElseThrow(InvalidCursorException::new);
  }
}
