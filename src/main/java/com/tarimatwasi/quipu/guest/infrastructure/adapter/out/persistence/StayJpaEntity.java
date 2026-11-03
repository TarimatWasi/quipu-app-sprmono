package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A stay is edited under a version (BE-SPR-DAT-13) and never deleted. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "stays")
public class StayJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "agreed_amount")
  private BigDecimal agreedAmount;

  /** TAR-237 (RN-20): the environment the stay occupies; null for a stay registered before. */
  @Column(name = "environment_id")
  private @Nullable Long environmentId;

  @Version private long version;

  protected StayJpaEntity() {}

  StayJpaEntity(
      Long guestId,
      Long environmentId,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal agreedAmount) {
    this.guestId = guestId;
    this.environmentId = environmentId;
    this.startDate = startDate;
    this.endDate = endDate;
    this.agreedAmount = agreedAmount;
  }

  /**
   * Copies the editable data of the domain object onto this row; the id and the guest never change.
   */
  void apply(Stay stay) {
    this.startDate = stay.startDate();
    this.endDate = stay.endDate();
    this.agreedAmount = stay.agreedAmount();
    this.environmentId = stay.environmentId();
  }

  Stay toDomain() {
    return new Stay(
        Objects.requireNonNull(getId()),
        guestId,
        startDate,
        endDate,
        agreedAmount,
        environmentId,
        version);
  }
}
