package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V22 (TAR-224): the indexes of the reads by date of the stays and of the payments of the stays and
 * contracts of the dashboard. Each one is checked by name, table, columns in order and that it is
 * neither unique nor partial, so dropping or changing any of them fails here.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class LookupIndexesMigrationTest {

  private static final String INDEX =
      """
      select t.relname as table_name, am.amname as method, x.indisunique as is_unique,
             x.indpred is null as is_total,
             array_to_string(array(
               select a.attname from unnest(x.indkey) with ordinality k(attnum, ord)
               join pg_attribute a on a.attrelid = x.indrelid and a.attnum = k.attnum
               order by k.ord), ',') as columns
      from pg_index x
      join pg_class i on i.oid = x.indexrelid
      join pg_class t on t.oid = x.indrelid
      join pg_namespace n on n.oid = i.relnamespace
      join pg_am am on am.oid = i.relam
      where n.nspname = 'public' and i.relname = ?
      """;

  @Autowired JdbcTemplate jdbc;

  @Test
  void stays_haveAnIndexByStartDate() {
    assertIndex("ix_stays_start_date", "stays", "start_date");
  }

  @Test
  void stays_haveAnIndexByEndDateThenStartDate() {
    assertIndex("ix_stays_end_date_start_date", "stays", "end_date,start_date");
  }

  @Test
  void payments_haveAnIndexByStayForEveryStatus() {
    assertIndex("ix_payments_stay", "payments", "stay_id");
  }

  @Test
  void payments_haveAnIndexByContractAndPeriodForEveryStatus() {
    assertIndex("ix_payments_contract_period", "payments", "contract_id,period");
  }

  private void assertIndex(String name, String table, String columns) {
    List<Map<String, Object>> rows = jdbc.queryForList(INDEX, name);

    assertThat(rows).as("index %s", name).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("table_name", table)
        .containsEntry("method", "btree")
        .containsEntry("is_unique", false)
        .containsEntry("is_total", true)
        .containsEntry("columns", columns);
  }
}
