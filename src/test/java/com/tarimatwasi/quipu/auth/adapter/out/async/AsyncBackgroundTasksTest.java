package com.tarimatwasi.quipu.auth.adapter.out.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The work runs in another thread and what it throws never reaches the caller. */
class AsyncBackgroundTasksTest {

  @Test
  void runsTheTaskInAnotherThreadAndReturnsAtOnce() throws Exception {
    try (var tasks = new AsyncBackgroundTasks()) {
      var release = new CountDownLatch(1);
      var ran = new CountDownLatch(1);
      var thread = new AtomicReference<String>();

      tasks.run(
          () -> {
            await(release);
            thread.set(Thread.currentThread().getName());
            ran.countDown();
          });

      // The task is still blocked, so run() returned without waiting for it.
      assertThat(ran.getCount()).isEqualTo(1);
      release.countDown();
      assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(thread.get())
          .startsWith("background-")
          .isNotEqualTo(Thread.currentThread().getName());
    }
  }

  @Test
  void aFailingTaskNeverReachesTheCaller() throws Exception {
    try (var tasks = new AsyncBackgroundTasks()) {
      var failed = new CountDownLatch(1);

      assertThatCode(
              () ->
                  tasks.run(
                      () -> {
                        failed.countDown();
                        throw new IllegalStateException("guest@example.test");
                      }))
          .doesNotThrowAnyException();
      assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void aFailureIsLoggedWithTheClassAndNeverTheMessage() throws Exception {
    var logger = (Logger) LoggerFactory.getLogger(AsyncBackgroundTasks.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try (var tasks = new AsyncBackgroundTasks()) {
      tasks.run(
          () -> {
            throw new IllegalStateException("guest@example.test");
          });
      tasks.stop();
      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .containsExactly("A background task failed: java.lang.IllegalStateException");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void stopWaitsForTheAcceptedTasks() {
    var release = new CountDownLatch(1);
    var finished = new AtomicBoolean();
    var tasks = new AsyncBackgroundTasks();
    tasks.start();
    tasks.run(
        () -> {
          await(release);
          finished.set(true);
        });
    var released =
        CompletableFuture.runAsync(
            release::countDown, CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS));

    tasks.stop();
    released.join();

    assertThat(finished).isTrue();
    assertThat(tasks.isRunning()).isFalse();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
