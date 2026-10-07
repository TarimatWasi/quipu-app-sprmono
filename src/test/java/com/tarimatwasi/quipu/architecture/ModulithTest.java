package com.tarimatwasi.quipu.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.QuipuApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** BE-SPR-ARQ-04, ARQ-05, ARQ-06, ARQ-07: module boundaries, APIs and cycles. */
class ModulithTest {

  @Test
  void modules_have_no_violations() {
    var modules = ApplicationModules.of(QuipuApplication.class);

    assertThat(modules.detectViolations().getMessages())
        .as("violations of the module boundaries, the exposed APIs and the cycles")
        .isEmpty();
    assertThat(modules.stream().map(m -> m.getIdentifier().toString()))
        .containsExactlyInAnyOrder("auth", "bff", "environment", "expense", "guest", "shared");
  }
}
