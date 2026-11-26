package com.tarimatwasi.quipu.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;

/** BE-SPR-DAT-04 and DAT-06: migrations apply and every table and column has COMMENT ON. */
@SpringBootTest
@ImportTestcontainers(PostgresContainers.class)
class MigrationDocumentationTest {

  private static final String UNDOCUMENTED_TABLES =
      """
      select c.relname from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
        and obj_description(c.oid, 'pg_class') is null
      """;

  private static final String UNDOCUMENTED_COLUMNS =
      """
      select c.relname || '.' || a.attname from pg_attribute a
      join pg_class c on c.oid = a.attrelid
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
        and a.attnum > 0 and not a.attisdropped
        and col_description(c.oid, a.attnum) is null
      """;

  /** Columns whose comment says they are not used yet, as table.column. */
  private static final String UNUSED_COLUMNS =
      """
      select c.relname || '.' || a.attname from pg_attribute a
      join pg_class c on c.oid = a.attrelid
      join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relkind = 'r' and a.attnum > 0 and not a.attisdropped
        and col_description(c.oid, a.attnum) ilike '%sin uso%'
        and col_description(c.oid, a.attnum) not ilike '%días sin uso%'
      """;

  private static final Path PERSISTENCE = Path.of("src/main/java");

  @Autowired JdbcTemplate jdbc;

  @Test
  void migrations_document_every_table_and_column() {
    assertThat(undocumented()).isEmpty();
  }

  @Test
  void detector_flags_a_table_without_comments() {
    jdbc.execute("create table tmp_undocumented (id int)");
    try {
      assertThat(undocumented()).contains("tmp_undocumented", "tmp_undocumented.id");
    } finally {
      jdbc.execute("drop table tmp_undocumented");
    }
  }

  private List<String> undocumented() {
    var all = jdbc.queryForList(UNDOCUMENTED_TABLES, String.class);
    all.addAll(jdbc.queryForList(UNDOCUMENTED_COLUMNS, String.class));
    return all;
  }

  /**
   * A comment that says a column is not used yet goes stale the day the code uses it (V4 fixed four
   * of them): such a column must not be named by any persistence adapter, as a column or a field.
   */
  @Test
  void aColumnDocumentedAsUnused_isNotUsedByTheCode() throws IOException {
    List<String> sources;
    try (Stream<Path> files = Files.walk(PERSISTENCE)) {
      sources =
          files
              .filter(file -> file.toUri().toString().contains("/adapter/out/"))
              .filter(file -> file.toString().endsWith(".java"))
              .map(MigrationDocumentationTest::read)
              .toList();
    }
    List<String> used =
        jdbc.queryForList(UNUSED_COLUMNS, String.class).stream()
            .filter(column -> namedIn(sources, column))
            .toList();
    assertThat(used).as("columns documented as unused that the code uses").isEmpty();
  }

  /** Named by an adapter of its table, as the column or as the field it maps to. */
  private static boolean namedIn(List<String> sources, String tableColumn) {
    int dot = tableColumn.indexOf('.');
    Pattern table = Pattern.compile("\\b" + Pattern.quote(tableColumn.substring(0, dot)) + "\\b");
    String column = tableColumn.substring(dot + 1);
    Pattern name =
        Pattern.compile(
            "\\b(" + Pattern.quote(column) + "|" + Pattern.quote(camel(column)) + ")\\b");
    return sources.stream()
        .filter(source -> table.matcher(source).find())
        .anyMatch(source -> name.matcher(source).find());
  }

  private static String camel(String column) {
    Matcher part = Pattern.compile("_(\\w)").matcher(column);
    return part.replaceAll(m -> m.group(1).toUpperCase(Locale.ROOT));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
