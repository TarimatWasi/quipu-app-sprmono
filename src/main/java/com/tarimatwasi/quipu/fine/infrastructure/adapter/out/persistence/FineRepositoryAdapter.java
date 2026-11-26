package com.tarimatwasi.quipu.fine.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.fine.application.port.out.FineGuestMissingException;
import com.tarimatwasi.quipu.fine.application.port.out.FineRepositoryPort;
import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.ViolatedConstraint;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class FineRepositoryAdapter implements FineRepositoryPort {

  /** The foreign key fk_fines_guest from fines to guests. */
  private static final String GUEST_FOREIGN_KEY = "fk_fines_guest";

  private final FineJpaRepository jpaRepository;

  FineRepositoryAdapter(FineJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  /** Flushed at once, so that a refusal of the foreign key surfaces here and not at the commit. */
  @Override
  public Fine insert(
      Long guestId, BigDecimal amount, YearMonth month, @Nullable String description) {
    try {
      return jpaRepository
          .saveAndFlush(new FineJpaEntity(guestId, amount, month, description))
          .toDomain();
    } catch (DataIntegrityViolationException e) {
      if (ViolatedConstraint.is(e, GUEST_FOREIGN_KEY)) {
        throw new FineGuestMissingException(e);
      }
      throw e;
    }
  }

  @Override
  public Optional<Fine> findById(Long id) {
    return jpaRepository.findById(id).map(FineJpaEntity::toDomain);
  }

  @Override
  public boolean delete(Long id) {
    return jpaRepository.deleteFine(id) > 0;
  }

  @Override
  public List<Fine> findByMonth(YearMonth month) {
    return jpaRepository.findByMonthOrderByCreatedDateAscIdAsc(month.atDay(1)).stream()
        .map(FineJpaEntity::toDomain)
        .toList();
  }

  @Override
  public Total totalOfMonth(YearMonth month) {
    BigDecimal sum = jpaRepository.sumAmountByMonth(month.atDay(1));
    return new Total(
        sum == null ? BigDecimal.ZERO : sum, jpaRepository.countByMonth(month.atDay(1)));
  }
}
