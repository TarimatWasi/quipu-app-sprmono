package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ADR-F4 with the real wiring of Spring Boot (executor of virtual threads, limit and rejection of
 * application.yml, here with a limit of 2) and a provider that answers only when the test lets it.
 */
class MailAsyncConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
          .withUserConfiguration(MailAsyncConfig.class, Wiring.class)
          .withPropertyValues(
              "spring.threads.virtual.enabled=true",
              "spring.task.execution.simple.concurrency-limit=2",
              "spring.task.execution.simple.reject-tasks-when-limit-reached=true");

  /** The fake Resend: every call waits for {@code release} and reports what it saw. */
  static final class Provider {
    final CountDownLatch started = new CountDownLatch(2);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch answered = new CountDownLatch(2);
    final AtomicBoolean onAVirtualThread = new AtomicBoolean();
    final AtomicBoolean releasedInTime = new AtomicBoolean();
  }

  @Configuration(proxyBeanMethods = false)
  static class Wiring {

    @Bean
    Provider provider() {
      return new Provider();
    }

    @Bean
    ResendMailSender resendMailSender(Provider provider) {
      var builder = RestClient.builder();
      MockRestServiceServer.bindTo(builder)
          .build()
          .expect(ExpectedCount.manyTimes(), requestTo("https://api.resend.com/emails"))
          .andRespond(
              request -> {
                provider.onAVirtualThread.set(Thread.currentThread().isVirtual());
                provider.started.countDown();
                try {
                  provider.releasedInTime.set(provider.release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                provider.answered.countDown();
                return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
              });
      return new ResendMailSender(
          builder, "key", "no-reply@example.test", "https://app.example.test");
    }

    @Bean
    ResendPasswordResetMailer mailer(ResendMailSender sender) {
      return new ResendPasswordResetMailer(sender);
    }
  }

  @Test
  void theApplicationTaskExecutorIsTheSimpleAsyncExecutorOfSpringBoot() {
    runner.run(
        context ->
            assertThat(context.getBean("applicationTaskExecutor"))
                .isInstanceOf(SimpleAsyncTaskExecutor.class));
  }

  @Test
  void theCallerDoesNotWaitForTheProviderWhichAnswersOnAVirtualThread() {
    runner.run(
        context -> {
          var provider = context.getBean(Provider.class);
          var mailer = context.getBean(ResendPasswordResetMailer.class);

          mailer.sendResetLink("a@example.test", "code-1", Duration.ofMinutes(30));
          mailer.sendResetLink("b@example.test", "code-2", Duration.ofMinutes(30));

          // both sends are inside the provider, which is still blocked, and the caller is back
          assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(provider.release.getCount()).isEqualTo(1);
          assertThat(provider.onAVirtualThread).isTrue();
          provider.release.countDown();
          assertThat(provider.answered.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(provider.releasedInTime).isTrue();
        });
  }

  @Test
  void aThirdSendWhileTwoAreRunningIsDroppedWithAWarningAndNeverFailsTheCaller() {
    var logger = (Logger) LoggerFactory.getLogger(ResendPasswordResetMailer.class);
    var logs = new ListAppender<ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      runner.run(
          context -> {
            var provider = context.getBean(Provider.class);
            var mailer = context.getBean(ResendPasswordResetMailer.class);
            mailer.sendResetLink("a@example.test", "code-1", Duration.ofMinutes(30));
            mailer.sendResetLink("b@example.test", "code-2", Duration.ofMinutes(30));
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();

            mailer.sendResetLink("c@example.test", "code-3", Duration.ofMinutes(30));

            assertThat(logs.list)
                .singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .asString()
                .contains("not queued")
                .doesNotContain("c@example.test")
                .doesNotContain("code-3");
            provider.release.countDown();
            assertThat(provider.answered.await(5, TimeUnit.SECONDS)).isTrue();
          });
    } finally {
      logger.detachAppender(logs);
    }
  }
}
