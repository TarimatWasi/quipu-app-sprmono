package com.tarimatwasi.quipu.auth.adapter.out.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/** The work runs in another thread and what it throws never reaches the caller. */
class AsyncBackgroundTasksTest {

  @Configuration(proxyBeanMethods = false)
  @EnableAsync
  static class Config {
    @Bean
    TaskExecutor taskExecutor() {
      return new SimpleAsyncTaskExecutor("test-");
    }

    @Bean
    AsyncBackgroundTasks tasks(AsyncBackgroundTasks.Executor executor) {
      return new AsyncBackgroundTasks(executor);
    }

    @Bean
    AsyncBackgroundTasks.Executor executor() {
      return new AsyncBackgroundTasks.Executor();
    }
  }

  @Test
  void runsTheTaskInAnotherThreadAndReturnsAtOnce() throws Exception {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      BackgroundTaskPort tasks = context.getBean(BackgroundTaskPort.class);
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
      assertThat(thread.get()).startsWith("test-").isNotEqualTo(Thread.currentThread().getName());
    }
  }

  @Test
  void aFailingTaskNeverReachesTheCaller() throws Exception {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      BackgroundTaskPort tasks = context.getBean(BackgroundTaskPort.class);
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

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
