package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-182: the stay data live only in stays; guests has neither the columns nor their checks. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class GuestStayColumnsMigrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void guests_noLongerHaveTheStayColumns() {
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = 'guests'",
                String.class))
        .contains("guest_type")
        .doesNotContain("stay_start_date", "stay_end_date", "agreed_amount");
  }

  @Test
  void guests_noLongerHaveTheChecksOfTheStayColumns() {
    assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'public.guests'::regclass",
                String.class))
        .contains("ck_guests_type")
        .doesNotContain("ck_guests_stay_dates", "ck_guests_agreed_amount");
  }
}
