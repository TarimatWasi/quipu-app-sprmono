package com.tarimatwasi.quipu.auth.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** BE-SPR-OBS-06 (TAR-174): the log never carries the whole email. */
class LoggingPasswordResetMailTest {

  private static final String LINK = "http://localhost:4200/reset-password?code=CODE123";

  private static String logFor(String email) {
    var logger = (Logger) LoggerFactory.getLogger(LoggingPasswordResetMail.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new LoggingPasswordResetMail("http://localhost:4200/")
          .sendResetLink(email, "CODE123", Duration.ofMinutes(30));
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list).hasSize(1);
    return appender.list.get(0).getFormattedMessage();
  }

  @Test
  void sendResetLink_writesTheLinkAndAMaskedEmail() {
    assertThat(logFor("admin@quipu.local"))
        .contains(LINK)
        .contains("a***@quipu.local")
        .doesNotContain("admin@quipu.local");
  }

  @Test
  void sendResetLink_withoutAtSign_hidesTheWholeValue() {
    assertThat(logFor("not-an-email")).contains(LINK).doesNotContain("not-an-email");
  }
}
