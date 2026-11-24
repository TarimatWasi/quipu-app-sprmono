package com.tarimatwasi.quipu.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** LockWaits is the only reader of pg_stat_activity, so none forgets to clear its snapshot. */
class LockWaitsTest {
  private static final Path TESTS = Path.of("src/test/java");

  @Test
  void onlyLockWaitsReadsTheActivityOfTheSessions() throws IOException {
    List<String> readers;
    try (Stream<Path> files = Files.walk(TESTS)) {
      readers =
          files
              .filter(file -> file.toString().endsWith(".java"))
              .filter(file -> !file.endsWith(Path.of("support", "LockWaits.java")))
              .filter(file -> !file.endsWith(Path.of("support", "LockWaitsTest.java")))
              .filter(LockWaitsTest::readsTheActivity)
              .map(Path::toString)
              .toList();
    }
    assertThat(readers).as("poll the locks through LockWaits").isEmpty();
  }

  private static boolean readsTheActivity(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8).contains("pg_stat_" + "activity");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
