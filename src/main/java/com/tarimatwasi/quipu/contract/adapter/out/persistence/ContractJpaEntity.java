package com.tarimatwasi.quipu.contract.adapter.out.persistence;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort.Draft;
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

/**
 * A contract is finished under a version (BE-SPR-DAT-13) and never deleted. Only ACTIVE and
 * TERMINATED are stored; EXPIRED is derived from the end date.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "contracts")
public class ContractJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  static final String ACTIVE = "ACTIVE";
  static final String TERMINATED = "TERMINATED";

  @Column(name = "guest_id")
  private Long guestId;

  @Column(name = "environment_id")
  private Long environmentId;

  @Column(name = "start_date")
  private LocalDate startDate;

  @Column(name = "end_date")
  private @Nullable LocalDate endDate;

  @Column(name = "monthly_amount")
  private BigDecimal monthlyAmount;

  @Column(name = "payment_day")
  private short paymentDay;

  @Column(name = "grace_days")
  private short graceDays;

  @Column(name = "file_key")
  private String fileKey;

  @Column(name = "original_name")
  private String originalName;

  @Column(name = "size_bytes")
  private long sizeBytes;

  private String status;

  @Version private long version;

  protected ContractJpaEntity() {}

  ContractJpaEntity(Draft draft) {
    this.guestId = draft.guestId();
    this.environmentId = draft.environmentId();
    this.startDate = draft.startDate();
    this.endDate = draft.endDate();
    this.monthlyAmount = draft.monthlyAmount();
    this.paymentDay = (short) draft.paymentDay();
    this.graceDays = (short) draft.graceDays();
    this.fileKey = draft.fileKey();
    this.originalName = draft.originalName();
    this.sizeBytes = draft.sizeBytes();
    this.status = ACTIVE;
  }

  /** Copies the only thing that changes after the load: the contract is finished by hand. */
  void apply(Contract contract) {
    this.status = contract.terminated() ? TERMINATED : ACTIVE;
  }

  Contract toDomain() {
    return new Contract(
        Objects.requireNonNull(getId()),
        environmentId,
        guestId,
        startDate,
        endDate,
        monthlyAmount,
        paymentDay,
        graceDays,
        fileKey,
        originalName,
        sizeBytes,
        TERMINATED.equals(status));
  }
}
