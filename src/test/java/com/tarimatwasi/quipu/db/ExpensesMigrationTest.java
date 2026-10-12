package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** BE-SPR-WEB-08 (TAR-176): the expenses table has the index the keyset listing walks. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ExpensesMigrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void expenses_haveAnIndexInTheOrderOfTheListing() {
    List<String> definitions =
        jdbc.queryForList(
            "SELECT pg_get_indexdef(indexrelid) FROM pg_index"
                + " WHERE indrelid = 'expenses'::regclass",
            String.class);

    assertThat(definitions)
        .anyMatch(definition -> definition.contains("(month, created_date, id)"));
  }
}
