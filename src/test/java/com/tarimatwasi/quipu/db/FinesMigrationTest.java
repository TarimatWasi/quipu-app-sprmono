package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V21 (TAR-47, RF-19): the fines table and what the base itself refuses. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class FinesMigrationTest {

  @Autowired JdbcTemplate jdbc;

  private Long guestId;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    guestId = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', '50000001', 'CONTRACT', 'ACTIVE')",
        guestId);
  }

  @AfterEach
  void leaveTheSharedTablesClean() {
    TestTables.clear(jdbc);
  }

  private int insert(Long guest, String amount, String month, String description) {
    return jdbc.update(
        "INSERT INTO fines (id, guest_id, amount, month, description)"
            + " VALUES (nextval('fines_seq'), ?, ?::numeric, ?::date, ?)",
        guest,
        amount,
        month,
        description);
  }

  @Test
  void aValidFineIsStored() {
    assertThat(insert(guestId, "15.50", "2026-09-01", "Ruido")).isEqualTo(1);
  }

  @Test
  void theAmountMustBeGreaterThanZero() {
    assertThatThrownBy(() -> insert(guestId, "0", "2026-09-01", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_fines_amount");
  }

  @Test
  void theMonthIsItsFirstDay() {
    assertThatThrownBy(() -> insert(guestId, "1", "2026-09-02", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_fines_month");
  }

  @Test
  void theGuestMustExist() {
    assertThatThrownBy(() -> insert(TestIds.next(), "1", "2026-09-01", null))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_fines_guest");
  }

  @Test
  void theDescriptionHasAtMostFiveHundredCharacters() {
    assertThat(insert(guestId, "1", "2026-09-01", "😀".repeat(500))).isEqualTo(1);
    assertThatThrownBy(() -> insert(guestId, "1", "2026-09-01", "x".repeat(501)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_fines_description");
  }

  @Test
  void theAmountHasTwoDecimalsAndEightDigitsBeforeThePoint() {
    assertThat(
            jdbc.queryForObject(
                "SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns"
                    + " WHERE table_name = 'fines' AND column_name = 'amount'",
                String.class))
        .isEqualTo("10,2");
  }

  @Test
  void fines_haveNoVersionColumn() {
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'fines'",
                String.class))
        .containsExactlyInAnyOrder(
            "id",
            "guest_id",
            "amount",
            "month",
            "description",
            "created_date",
            "last_modified_date",
            "created_by_id",
            "last_modified_by_id");
  }

  @Test
  void fines_haveAnIndexByMonth() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = 'fines'::regclass",
            String.class);

    assertThat(definitions).anyMatch(definition -> definition.endsWith("(month)"));
  }
}
