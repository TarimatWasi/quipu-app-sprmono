package com.tarimatwasi.quipu.payment.infrastructure.adapter.out.persistence;

import com.tarimatwasi.quipu.payment.application.port.out.PaymentRepositoryPort.NewProof;
import com.tarimatwasi.quipu.payment.domain.PaymentProof;
import com.tarimatwasi.quipu.payment.domain.ProofAuthor;
import com.tarimatwasi.quipu.payment.domain.ProofFile;
import com.tarimatwasi.quipu.payment.domain.ProofKind;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditorJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.AbstractAuditable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One proof of a payment (RN-22); the row of its payment is locked while it changes. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "payment_proofs")
public class PaymentProofJpaEntity extends AbstractAuditable<AuditorJpaEntity, Long> {

  @Column(name = "payment_id")
  private Long paymentId;

  @Enumerated(EnumType.STRING)
  private ProofKind kind;

  @Column(name = "declared_amount")
  private BigDecimal declaredAmount;

  @Column(name = "verified_amount")
  private @Nullable BigDecimal verifiedAmount;

  private @Nullable String method;

  @Column(name = "file_key")
  private @Nullable String fileKey;

  @Column(name = "original_name")
  private @Nullable String originalName;

  @Column(name = "content_type")
  private @Nullable String contentType;

  @Column(name = "size_bytes")
  private @Nullable Long sizeBytes;

  @Enumerated(EnumType.STRING)
  @Column(name = "uploaded_by")
  private ProofAuthor uploadedBy;

  @Column(name = "received_on")
  private @Nullable LocalDate receivedOn;

  protected PaymentProofJpaEntity() {}

  PaymentProofJpaEntity(Long paymentId, NewProof proof) {
    this.paymentId = paymentId;
    this.kind = proof.kind();
    this.declaredAmount = proof.declaredAmount();
    this.verifiedAmount = proof.verifiedAmount();
    this.method = proof.method();
    setFile(proof.file());
    this.uploadedBy = proof.uploadedBy();
    this.receivedOn = proof.receivedOn();
  }

  Long paymentId() {
    return paymentId;
  }

  /** Copies what a correction or a verification can change. */
  void apply(PaymentProof proof) {
    this.kind = proof.kind();
    this.declaredAmount = proof.declaredAmount();
    this.verifiedAmount = proof.verifiedAmount();
    this.method = proof.method();
    setFile(proof.file());
  }

  private void setFile(@Nullable ProofFile file) {
    this.fileKey = file == null ? null : file.key();
    this.originalName = file == null ? null : file.originalName();
    this.contentType = file == null ? null : file.type().contentType();
    this.sizeBytes = file == null ? null : file.sizeBytes();
  }

  PaymentProof toDomain() {
    String key = fileKey;
    String name = originalName;
    String type = contentType;
    Long size = sizeBytes;
    ProofFile file =
        key == null || name == null || type == null || size == null
            ? null
            : new ProofFile(key, name, VoucherType.ofContentType(type), size);
    return new PaymentProof(
        Objects.requireNonNull(getId()),
        kind,
        declaredAmount,
        verifiedAmount,
        method,
        file,
        uploadedBy,
        receivedOn,
        getCreatedDate().orElseThrow().atZone(ZoneId.systemDefault()).toInstant());
  }
}
