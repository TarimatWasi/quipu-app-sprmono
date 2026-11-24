package com.tarimatwasi.quipu.shared.events.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(OutputCaptureExtension.class)
class AsyncFailureLogTest {

  /** BE-SPR-OBS-06: the message of a database error carries the values of the row. */
  @Test
  void aFailureIsLoggedByItsMethodAndClassNeverItsMessage(CapturedOutput output)
      throws NoSuchMethodException {
    new AsyncFailureLog()
        .getAsyncUncaughtExceptionHandler()
        .handleUncaughtException(
            new DataIntegrityViolationException("Key (email)=(ana@example.test) already exists"),
            Object.class.getMethod("toString"),
            "event");

    assertThat(output)
        .contains("An asynchronous call failed: Object.toString")
        .contains(DataIntegrityViolationException.class.getName())
        .doesNotContain("ana@example.test");
  }
}
