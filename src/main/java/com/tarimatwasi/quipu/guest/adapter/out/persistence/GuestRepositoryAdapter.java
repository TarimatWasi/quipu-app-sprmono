package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

@Component
public class GuestRepositoryAdapter implements GuestRepositoryPort {

  private static final String EMAIL_CONSTRAINT = "uq_guests_email_lower";
  private static final String DOCUMENT_CONSTRAINT = "uq_guests_document";

  private final GuestJpaRepository jpaRepository;

  GuestRepositoryAdapter(GuestJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Optional<Guest> findById(Long id) {
    return jpaRepository.findById(id).map(GuestJpaEntity::toDomain);
  }

  @Override
  public Optional<Guest> findByIdForUpdate(Long id) {
    return jpaRepository.findWithLockById(id).map(GuestJpaEntity::toDomain);
  }

  @Override
  public CursorPage<Guest> findAll(
      @Nullable Collection<GuestStatus> statuses, @Nullable GuestType type, PageQuery page) {
    DocumentKey after = page.after() == null ? null : DocumentKey.decode(page.after());
    Specification<GuestJpaEntity> filter =
        (root, query, builder) -> {
          List<Predicate> where = new ArrayList<>();
          if (statuses != null) {
            where.add(root.get("status").in(statuses));
          }
          if (type != null) {
            where.add(builder.equal(root.get("type"), type));
          }
          if (after != null) {
            // (documentType, documentNumber) > (type, number), in the order of the listing.
            Path<DocumentType> documentType = root.get("documentType");
            where.add(
                builder.or(
                    builder.greaterThan(documentType, after.documentType()),
                    builder.and(
                        builder.equal(documentType, after.documentType()),
                        builder.greaterThan(
                            root.<String>get("documentNumber"), after.documentNumber()))));
          }
          return builder.and(where.toArray(Predicate[]::new));
        };
    Sort order = Sort.by("documentType", "documentNumber");
    // One more than the limit: the extra row only says that there is a next page.
    List<GuestJpaEntity> rows =
        jpaRepository.findBy(filter, query -> query.sortBy(order).limit(page.limit() + 1).all());
    return CursorPage.of(
        rows.stream().map(GuestJpaEntity::toDomain).toList(),
        page.limit(),
        guest -> CursorToken.encode(guest.documentType().name(), guest.documentNumber()));
  }

  /** The sort key of a guest, as the cursor carries it. */
  private record DocumentKey(DocumentType documentType, String documentNumber) {

    static DocumentKey decode(String cursor) {
      List<String> parts = CursorToken.decode(cursor, 2);
      try {
        return new DocumentKey(DocumentType.valueOf(parts.get(0)), parts.get(1));
      } catch (IllegalArgumentException unknownType) {
        throw new InvalidCursorException();
      }
    }
  }

  @Override
  public Optional<Guest> findByDocument(DocumentType documentType, String documentNumber) {
    return jpaRepository
        .findByDocumentTypeAndDocumentNumber(documentType, documentNumber)
        .map(GuestJpaEntity::toDomain);
  }

  @Override
  public Guest create(
      DocumentType documentType,
      String documentNumber,
      GuestType type,
      @Nullable String phone,
      @Nullable String email) {
    try {
      return jpaRepository
          .saveAndFlush(new GuestJpaEntity(documentType, documentNumber, type, phone, email))
          .toDomain();
    } catch (DataIntegrityViolationException e) {
      throw conflict(e);
    }
  }

  /** Flushes so that the unique violation of two simultaneous writes surfaces here, not later. */
  @Override
  public Guest save(Guest guest) {
    GuestJpaEntity entity =
        jpaRepository
            .findWithLockById(guest.id())
            .orElseThrow(() -> new NoSuchElementException("guest " + guest.id()));
    entity.apply(guest);
    try {
      return jpaRepository.saveAndFlush(entity).toDomain();
    } catch (DataIntegrityViolationException e) {
      throw conflict(e);
    }
  }

  /**
   * Which unique rule broke is read from the name of the constraint, not guessed: a repeated email
   * must not be reported as a repeated document. Any other failure stays as it is (TAR-207).
   */
  static RuntimeException conflict(DataIntegrityViolationException e) {
    if (ViolatedConstraint.is(e, EMAIL_CONSTRAINT)) {
      return new GuestEmailAlreadyExistsException(e);
    }
    if (ViolatedConstraint.is(e, DOCUMENT_CONSTRAINT)) {
      return new GuestDocumentAlreadyExistsException(e);
    }
    return e;
  }
}
