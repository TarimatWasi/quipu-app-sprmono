package com.tarimatwasi.quipu.auth.adapter.out.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
