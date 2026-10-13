package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentAlreadyExistsException;
import com.tarimatwasi.quipu.guest.port.out.GuestEmailAlreadyExistsException;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** BE-SPR-WEB-08 (TAR-176): guests are read by last index, ordered by document type and number. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class GuestRepositoryAdapterTest {

  @Autowired GuestRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    insert("DNI", "30000001", "CONTRACT", "ACTIVE");
    insert("CE", "20000001", "TEMPORARY", "ACTIVE");
    insert("DNI", "10000002", "CONTRACT", "INACTIVE");
    insert("PASSPORT", "P0000001", "TEMPORARY", "ACTIVE");
    insert("DNI", "10000001", "CONTRACT", "ACTIVE");
  }

  private void insert(String documentType, String number, String type, String status) {
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, ?, ?, ?, ?)",
        TestIds.next(),
        documentType,
        number,
        type,
        status);
  }

  private static String key(Guest guest) {
    return guest.documentType() + "/" + guest.documentNumber();
  }

  @Test
  void findAll_walksTheWholeListInDocumentOrderWithoutRepeatingOrSkipping() {
    List<String> keys = new ArrayList<>();
    PageQuery query = new PageQuery(null, 2);
    int pages = 0;
    do {
      CursorPage<Guest> page = adapter.findAll(null, null, query);
      page.content().forEach(guest -> keys.add(key(guest)));
      query = new PageQuery(page.nextCursor(), 2);
      pages++;
    } while (query.after() != null);

    assertThat(keys)
        .containsExactly(
            "CE/20000001", "DNI/10000001", "DNI/10000002", "DNI/30000001", "PASSPORT/P0000001");
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void findAll_appliesTheStatusAndTypeFiltersAcrossPages() {
    CursorPage<Guest> first =
        adapter.findAll(List.of(GuestStatus.ACTIVE), GuestType.CONTRACT, new PageQuery(null, 1));
    CursorPage<Guest> second =
        adapter.findAll(
            List.of(GuestStatus.ACTIVE), GuestType.CONTRACT, new PageQuery(first.nextCursor(), 1));

    assertThat(first.content())
        .extracting(GuestRepositoryAdapterTest::key)
        .containsExactly("DNI/10000001");
    assertThat(second.content())
        .extracting(GuestRepositoryAdapterTest::key)
        .containsExactly("DNI/30000001");
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  void findAll_withAnInvalidCursor_isRefused() {
    assertThatThrownBy(() -> adapter.findAll(null, null, new PageQuery("garbage!", 5)))
        .isInstanceOf(InvalidCursorException.class);
    String unknownType = CursorToken.encode("NOT_A_TYPE", "123");
    assertThatThrownBy(() -> adapter.findAll(null, null, new PageQuery(unknownType, 5)))
        .isInstanceOf(InvalidCursorException.class);
    String oneKeyOnly = CursorToken.encode("DNI");
    assertThatThrownBy(() -> adapter.findAll(null, null, new PageQuery(oneKeyOnly, 5)))
        .isInstanceOf(InvalidCursorException.class);
  }

  /** TAR-186 (D-33): a repeated contact email is not reported as a repeated document. */
  @Test
  void save_withAnEmailOfAnotherGuest_reportsTheEmailNotTheDocument() {
    Long first = idOf("DNI", "30000001");
    Long second = idOf("CE", "20000001");
    jdbc.update("UPDATE guests SET email = ? WHERE id = ?", "ana@example.test", first);
    Guest changed = withEmail(adapter.findById(second).orElseThrow(), "ANA@example.test");

    assertThatThrownBy(() -> transaction.execute(status -> adapter.save(changed)))
        .isInstanceOf(GuestEmailAlreadyExistsException.class);
  }

  @Test
  void save_withTheDocumentOfAnotherGuest_reportsTheDocument() {
    Long second = idOf("CE", "20000001");
    Guest changed = withDocument(adapter.findById(second).orElseThrow(), "DNI", "30000001");

    assertThatThrownBy(() -> transaction.execute(status -> adapter.save(changed)))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
  }

  @Test
  void conflict_readsTheNameOfTheConstraint() {
    var email =
        new ConstraintViolationException("dup", new SQLException(), "uq_guests_email_lower");
    var other = new ConstraintViolationException("dup", new SQLException(), "uq_guests_document");

    assertThat(GuestRepositoryAdapter.conflict(new DataIntegrityViolationException("x", email)))
        .isInstanceOf(GuestEmailAlreadyExistsException.class);
    assertThat(GuestRepositoryAdapter.conflict(new DataIntegrityViolationException("x", other)))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
    assertThat(GuestRepositoryAdapter.conflict(new DataIntegrityViolationException("x")))
        .isInstanceOf(GuestDocumentAlreadyExistsException.class);
  }

  private Long idOf(String documentType, String number) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT id FROM guests WHERE document_type = ? AND document_number = ?",
            Long.class,
            documentType,
            number));
  }

  private static Guest withEmail(Guest guest, String email) {
    return new Guest(
        guest.id(),
        guest.documentType(),
        guest.documentNumber(),
        guest.fullName(),
        email,
        guest.phone(),
        guest.type(),
        guest.status(),
        guest.statusBeforeInactive(),
        guest.personalDataCompletedAt(),
        guest.documentsStepCompletedAt(),
        guest.stayStartDate(),
        guest.stayEndDate(),
        guest.agreedAmount(),
        guest.emergencyContact());
  }

  private static Guest withDocument(Guest guest, String documentType, String number) {
    return new Guest(
        guest.id(),
        DocumentType.valueOf(documentType),
        number,
        guest.fullName(),
        guest.email(),
        guest.phone(),
        guest.type(),
        guest.status(),
        guest.statusBeforeInactive(),
        guest.personalDataCompletedAt(),
        guest.documentsStepCompletedAt(),
        guest.stayStartDate(),
        guest.stayEndDate(),
        guest.agreedAmount(),
        guest.emergencyContact());
  }
}
