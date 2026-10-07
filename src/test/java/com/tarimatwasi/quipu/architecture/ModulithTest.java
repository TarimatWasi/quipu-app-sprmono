package com.tarimatwasi.quipu.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.QuipuApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.modulith.core.ApplicationModules;

/** BE-SPR-ARQ-04, ARQ-05, ARQ-06, ARQ-07: module boundaries, APIs and cycles. */
class ModulithTest {

  /**
   * TAR-62 PR2: the imported code still has these violations by design. The resource holds their
   * exact messages (member, type and module, without line numbers), so a new violation on any other
   * type or member is not covered by it. PR2 empties the resource; this test fails when a listed
   * violation disappears, so the entries are deleted as they are fixed.
   */
  private static final String KNOWN_UNTIL_PR2 = "modulith-known-violations.txt";

  @Test
  void modules_have_exactly_the_known_violations() {
    var modules = ApplicationModules.of(QuipuApplication.class);

    var actual = modules.detectViolations().getMessages().stream().map(m -> normalize(m)).sorted();

    assertThat(actual)
        .as("violations of the modules, compared with %s (TAR-62 PR2)", KNOWN_UNTIL_PR2)
        .containsExactlyElementsOf(known());
    assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
        .containsExactlyInAnyOrder("auth", "bff", "environment", "expense", "guest", "shared");
  }

  @Test
  void a_new_violation_on_another_type_is_not_covered_by_the_known_list() {
    var onAnotherType =
        normalize(
            "Module 'bff' depends on non-exposed type com.tarimatwasi.quipu.auth.domain.UserAccount"
                + " within module 'auth'!\nField <...AuthBffController.account> has type"
                + " <...UserAccount> in (AuthBffController.java:99)");

    assertThat(known()).doesNotContain(onAnotherType);
  }

  /** Normalizes line endings and drops the line numbers, which move with every edit. */
  static String normalize(String message) {
    return message.replace("\r\n", "\n").replaceAll("\\((\\w+\\.java):\\d+\\)", "($1)").strip();
  }

  private static List<String> known() {
    try (var in = new ClassPathResource(KNOWN_UNTIL_PR2).getInputStream()) {
      var content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
      return Arrays.stream(content.split("\n----\n")).map(String::strip).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
