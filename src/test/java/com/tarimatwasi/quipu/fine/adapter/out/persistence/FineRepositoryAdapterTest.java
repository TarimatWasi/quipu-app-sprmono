package com.tarimatwasi.quipu.fine.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.fine.domain.Fine;
import com.tarimatwasi.quipu.fine.port.out.FineGuestMissingException;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** RF-19 against PostgreSQL: the fines of a month, the free deletion (RN-24) and the guest FK. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class FineRepositoryAdapterTest {

  private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

  @Autowired FineRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  private Long guestId;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    guestId = insertGuest("ACTIVE");
  }

  private Fine insert(String amount, YearMonth month) {
    return transaction.execute(
        status -> adapter.insert(guestId, new BigDecimal(amount), month, null));
  }

  @Test
  void insert_storesTheFineOnTheFirstDayOfItsMonth() {
    Fine fine =
        transaction.execute(
            status -> adapter.insert(guestId, new BigDecimal("15.50"), SEPTEMBER, "Ruido"));

    assertThat(fine.guestId()).isEqualTo(guestId);
    assertThat(fine.amount()).isEqualByComparingTo("15.50");
    assertThat(fine.month()).isEqualTo(SEPTEMBER);
    assertThat(fine.description()).isEqualTo("Ruido");
    assertThat(
            jdbc.queryForObject(
                "SELECT month::text FROM fines WHERE id = ?", String.class, fine.id()))
        .isEqualTo("2026-09-01");
  }

  @Test
  void insert_acceptsAnInactiveGuest() {
    Long inactive = insertGuest("INACTIVE");

    Fine fine =
        transaction.execute(status -> adapter.insert(inactive, BigDecimal.ONE, SEPTEMBER, null));

    assertThat(fine.guestId()).isEqualTo(inactive);
  }

  /** The race of TAR-47: a guest id that is not in guests is refused by the FK, not with a 500. */
  @Test
  void insert_forAGuestThatDoesNotExist_isAMissingGuest() {
    Long missing = TestIds.next();

    assertThatThrownBy(
            () ->
                transaction.execute(
                    status -> adapter.insert(missing, BigDecimal.ONE, SEPTEMBER, null)))
        .isInstanceOf(FineGuestMissingException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM fines", Integer.class)).isZero();
  }

  @Test
  void findByMonth_returnsOnlyThatMonthOldestFirst() {
    Fine first = insert("1", SEPTEMBER);
    insert("2", YearMonth.of(2026, 8));
    Fine second = insert("3", SEPTEMBER);
    insert("4", YearMonth.of(2026, 10));

    List<Fine> found = adapter.findByMonth(SEPTEMBER);

    assertThat(found).extracting(Fine::id).containsExactly(first.id(), second.id());
  }

  @Test
  void delete_removesTheRowForGood() {
    Fine fine = insert("5", SEPTEMBER);

    Boolean deleted = transaction.execute(status -> adapter.delete(fine.id()));

    assertThat(deleted).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM fines WHERE id = ?", Integer.class, fine.id()))
        .isZero();
  }

  @Test
  void delete_ofAMissingFine_returnsFalse() {
    Boolean deleted = transaction.execute(status -> adapter.delete(987_654L));

    assertThat(deleted).isFalse();
  }

  private Long insertGuest(String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', ?)",
        id,
        String.valueOf(id).substring(2),
        status);
    return id;
  }
}
