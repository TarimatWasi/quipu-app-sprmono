package com.tarimatwasi.quipu.auth.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
class ResendPasswordResetMailerTest {

  @Mock ResendMailSender sender;

  @Test
  void handsTheEmailToTheAsyncSender() {
    new ResendPasswordResetMailer(sender)
        .sendResetLink("guest@example.test", "code", Duration.ofMinutes(30));

    verify(sender).send("guest@example.test", "code", Duration.ofMinutes(30));
  }

  /** RF-22: in the caller's thread, and what the provider answered reaches the caller. */
  @Test
  void theAccessLinkOfAnAdministratorWaitsForTheSenderAndTellsWhetherItLeft() {
    when(sender.sendAdministratorAccessNow("admin@example.test", "code", Duration.ofMinutes(30)))
        .thenReturn(true, false);
    var mailer = new ResendPasswordResetMailer(sender);

    assertThat(mailer.sendAccessLinkNow("admin@example.test", "code", Duration.ofMinutes(30)))
        .isTrue();
    assertThat(mailer.sendAccessLinkNow("admin@example.test", "code", Duration.ofMinutes(30)))
        .isFalse();
    verify(sender, never()).send(anyString(), anyString(), any());
  }

  @Test
  void anExecutorAtItsLimitDropsTheEmailWithoutTheAddressOrTheCode() {
    doThrow(new TaskRejectedException("limit reached"))
        .when(sender)
        .send("guest@example.test", "code-123", Duration.ofMinutes(30));
    var logger = (Logger) LoggerFactory.getLogger(ResendPasswordResetMailer.class);
    var logs = new ListAppender<ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThatCode(
              () ->
                  new ResendPasswordResetMailer(sender)
                      .sendResetLink("guest@example.test", "code-123", Duration.ofMinutes(30)))
          .doesNotThrowAnyException();
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("not queued")
        .doesNotContain("guest@example.test")
        .doesNotContain("code-123");
  }
}
