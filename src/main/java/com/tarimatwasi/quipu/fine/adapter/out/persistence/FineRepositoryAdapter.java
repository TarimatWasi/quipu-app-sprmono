package com.tarimatwasi.quipu.fine.adapter.out.persistence;

import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.fine.port.out.FineGuestMissingException;
import com.tarimatwasi.quipu.fine.port.out.FineRepositoryPort;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class FineRepositoryAdapter implements FineRepositoryPort {

  /** The foreign key of V21 from fines to guests. */
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
      if (violates(e, GUEST_FOREIGN_KEY)) {
        throw new FineGuestMissingException(e);
      }
      throw e;
    }
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

  /**
   * Whether the write broke that constraint, read from the name the server reports so that the
   * language of its messages does not matter; the name Hibernate parses is only a fallback.
   */
  private static boolean violates(Throwable failure, String constraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql) {
        ServerErrorMessage server = psql.getServerErrorMessage();
        if (server != null && server.getConstraint() != null) {
          return constraint.equals(server.getConstraint());
        }
      }
    }
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return constraint.equals(violation.getConstraintName());
      }
    }
    return false;
  }
}
