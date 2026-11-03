package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TAR-239: V5 carries the payments of before into the new model. Every voucher becomes the only
 * proof of its payment, every resolution its only review, and of the rejections of a period or a
 * stay only the newest one without a payment that holds it stays REJECTED (correctable); the rest
 * are SUPERSEDED. It runs on a schema of its own, migrated up to V4 first.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class PaymentProofsMigrationTest {

  private static final String SCHEMA = "tar239";

  @Autowired DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void migrateUpToV4() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    flyway("4").migrate();
  }

  @AfterEach
  void dropSchema() {
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .createSchemas(true)
        .target(target)
        .load();
  }

  @Test
  void thePaymentsOfBeforeKeepTheirVoucherTheirDecisionAndOneCorrectableRejection() {
    String s = SCHEMA + ".";
    seedBeforeV5(s);

    flyway("latest").migrate();

    assertThat(
            jdbc.queryForList(
                "SELECT id || ':' || status FROM " + s + "payments ORDER BY id", String.class))
        .containsExactly(
            "100:APPROVED",
            "101:SUPERSEDED",
            "102:PENDING",
            "103:SUPERSEDED",
            "104:REJECTED",
            "105:REJECTED");
    theVouchersAndTheDecisionsAreCarried(s);
  }

  /**
   * October approved; September rejected, then a pending one; August rejected twice; a stay
   * rejected once.
   */
  private void seedBeforeV5(String s) {
    jdbc.update(
        "INSERT INTO "
            + s
            + "users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (1, 'a@x.test', 'DNI', '00000001', 'h',"
            + " 'ADMIN', FALSE, 'ACTIVE')");
    jdbc.update(
        "INSERT INTO "
            + s
            + "guests (id, full_name, document_type, document_number, guest_type, status) VALUES"
            + " (10, 'Ana', 'DNI', '12345678', 'CONTRACT', 'ACTIVE'),"
            + " (11, NULL, 'DNI', '87654321', 'TEMPORARY', 'ACTIVE')");
    jdbc.update(
        "INSERT INTO "
            + s
            + "environments (id, code, type, status) VALUES (20, '201', 'ROOM',"
            + " 'ACTIVE')");
    jdbc.update(
        "INSERT INTO "
            + s
            + "contracts (id, guest_id, environment_id, start_date, monthly_amount, payment_day,"
            + " grace_days, file_key, original_name, size_bytes) VALUES (30, 10, 20, '2026-01-01',"
            + " 800, 5, 3, 'contracts/a.pdf', 'a.pdf', 10)");
    jdbc.update(
        "INSERT INTO "
            + s
            + "stays (id, guest_id, start_date, end_date, agreed_amount) VALUES (40, 11,"
            + " '2026-10-01', '2026-10-20', 150)");
    payment(s, 100, "30", "NULL", "'2026-10-01'", "APPROVED", "2026-10-02");
    payment(s, 101, "30", "NULL", "'2026-09-01'", "REJECTED", "2026-09-02");
    payment(s, 102, "30", "NULL", "'2026-09-01'", "PENDING", "2026-09-05");
    payment(s, 103, "30", "NULL", "'2026-08-01'", "REJECTED", "2026-08-02");
    payment(s, 104, "30", "NULL", "'2026-08-01'", "REJECTED", "2026-08-06");
    payment(s, 105, "NULL", "40", "NULL", "REJECTED", "2026-10-03");
  }

  private void theVouchersAndTheDecisionsAreCarried(String s) {
    assertThat(
            jdbc.queryForMap(
                "SELECT kind, declared_amount, verified_amount, method, file_key, uploaded_by FROM "
                    + s
                    + "payment_proofs WHERE payment_id = 100"))
        .containsEntry("kind", "VOUCHER")
        .containsEntry("method", "Yape")
        .containsEntry("file_key", "vouchers/100")
        .containsEntry("uploaded_by", "GUEST");
    assertThat(
            jdbc.queryForObject(
                "SELECT verified_amount FROM " + s + "payments WHERE id = 100",
                java.math.BigDecimal.class))
        .isEqualByComparingTo("800");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + s + "payment_proofs", Integer.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForList(
                "SELECT payment_id || ':' || decision FROM "
                    + s
                    + "payment_reviews ORDER BY payment_id",
                String.class))
        .containsExactly(
            "100:APPROVED", "101:REJECTED", "103:REJECTED", "104:REJECTED", "105:REJECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + s + "payments WHERE submitted_at <> created_date",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                    + " AND table_name = 'payments'",
                String.class,
                SCHEMA))
        .contains("submitted_at", "verified_amount")
        .doesNotContain(
            "voucher_key", "original_name", "content_type", "size_bytes", "payment_method");
  }

  private void payment(
      String s, long id, String contract, String stay, String period, String status, String day) {
    boolean resolved = !"PENDING".equals(status);
    jdbc.update(
        "INSERT INTO "
            + s
            + "payments (id, guest_id, contract_id, stay_id, period, amount, payment_method,"
            + " voucher_key, original_name, content_type, size_bytes, status, rejection_reason,"
            + " reviewed_at, reviewed_by_id, recorded_by, created_date) VALUES ("
            + id
            + ", "
            + ("NULL".equals(stay) ? "10" : "11")
            + ", "
            + contract
            + ", "
            + stay
            + ", "
            + period
            + ", 800, 'Yape', 'vouchers/"
            + id
            + "', 'v.pdf', 'application/pdf', 10, '"
            + status
            + "', "
            + ("REJECTED".equals(status) ? "'No coincide'" : "NULL")
            + ", "
            + (resolved ? "'" + day + "T12:00:00Z'" : "NULL")
            + ", "
            + (resolved ? "1" : "NULL")
            + ", 'GUEST', '"
            + day
            + "T10:00:00Z')");
  }
}
