package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.EmergencyContact;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.shared.adapter.out.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "guests")
public class GuestJpaEntity extends AuditableEntity {

  @Id private UUID id;

  @Column(name = "document_type")
  @Enumerated(EnumType.STRING)
  private DocumentType documentType;

  @Column(name = "document_number")
  private String documentNumber;

  @Column(name = "full_name")
  private @Nullable String fullName;

  private @Nullable String email;

  private @Nullable String phone;

  @Column(name = "guest_type")
  @Enumerated(EnumType.STRING)
  private GuestType type;

  @Enumerated(EnumType.STRING)
  private GuestStatus status;

  @Column(name = "status_before_inactive")
  @Enumerated(EnumType.STRING)
  private @Nullable GuestStatus statusBeforeInactive;

  @Column(name = "personal_data_completed_at")
  private @Nullable Instant personalDataCompletedAt;

  @Column(name = "documents_step_completed_at")
  private @Nullable Instant documentsStepCompletedAt;

  @Column(name = "stay_start_date")
  private @Nullable LocalDate stayStartDate;

  @Column(name = "stay_end_date")
  private @Nullable LocalDate stayEndDate;

  @Column(name = "agreed_amount")
  private @Nullable BigDecimal agreedAmount;

  @Column(name = "emergency_contact_name")
  private @Nullable String emergencyContactName;

  @Column(name = "emergency_contact_relationship")
  private @Nullable String emergencyContactRelationship;

  @Column(name = "emergency_contact_phone")
  private @Nullable String emergencyContactPhone;

  @Version private long version;

  protected GuestJpaEntity() {}

  /** Copies the state of the domain object onto this row; the id never changes. */
  void apply(Guest guest) {
    this.documentType = guest.documentType();
    this.documentNumber = guest.documentNumber();
    this.fullName = guest.fullName();
    this.email = guest.email();
    this.phone = guest.phone();
    this.type = guest.type();
    this.status = guest.status();
    this.statusBeforeInactive = guest.statusBeforeInactive();
    this.personalDataCompletedAt = guest.personalDataCompletedAt();
    this.documentsStepCompletedAt = guest.documentsStepCompletedAt();
    this.stayStartDate = guest.stayStartDate();
    this.stayEndDate = guest.stayEndDate();
    this.agreedAmount = guest.agreedAmount();
    EmergencyContact contact = guest.emergencyContact();
    this.emergencyContactName = contact == null ? null : contact.name();
    this.emergencyContactRelationship = contact == null ? null : contact.relationship();
    this.emergencyContactPhone = contact == null ? null : contact.phone();
  }

  Guest toDomain() {
    boolean hasContact =
        emergencyContactName != null
            || emergencyContactRelationship != null
            || emergencyContactPhone != null;
    return new Guest(
        id,
        documentType,
        documentNumber,
        fullName,
        email,
        phone,
        type,
        status,
        statusBeforeInactive,
        personalDataCompletedAt,
        documentsStepCompletedAt,
        stayStartDate,
        stayEndDate,
        agreedAmount,
        hasContact
            ? new EmergencyContact(
                emergencyContactName, emergencyContactRelationship, emergencyContactPhone)
            : null);
  }
}
