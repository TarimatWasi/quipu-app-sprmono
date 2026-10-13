package com.tarimatwasi.quipu.auth.adapter.out.async;

import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * Runs the task in a virtual thread of its own executor (ADR-F4), so the answer of "forgot
 * password" does not depend on whether the account exists. The executor is not the shared {@code
 * applicationTaskExecutor}: the task itself submits the recovery email to that one, and sharing the
 * limit would let the outer tasks starve their own emails. When the limit is reached the task is
 * dropped with a warning; the person asks again once the cooldown passes.
 *
 * <p>The component is a {@link SmartLifecycle} so that, on shutdown, it waits for the accepted
 * tasks in the stop phase, before Spring destroys the beans the tasks use (database, mail
 * executor). The wait is on the permits, not on {@code SimpleAsyncTaskExecutor.close()}, because
 * that one interrupts the running tasks. It gives up after {@value #TERMINATION_TIMEOUT_SECONDS}
 * seconds.
 */
@Component
public class AsyncBackgroundTasks implements BackgroundTaskPort, SmartLifecycle, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncBackgroundTasks.class);

  private static final int CONCURRENCY_LIMIT = 10;
  private static final long TERMINATION_TIMEOUT_SECONDS = 8;

  private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
  private final Semaphore permits = new Semaphore(CONCURRENCY_LIMIT);
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean stopping = new AtomicBoolean();

  public AsyncBackgroundTasks() {
    executor.setThreadFactory(
        Thread.ofVirtual()
            .name("background-", 0)
            // Only the class: the message of an exception can carry an email address.
            .uncaughtExceptionHandler(
                (thread, e) -> LOG.error("A background task failed: {}", e.getClass().getName()))
            .factory());
  }

  /** A failure inside the task never reaches the caller: the thread logs it. */
  @Override
  public void run(Runnable task) {
    if (stopping.get() || !permits.tryAcquire()) {
      LOG.warn("A background task was dropped: the executor is at its limit or stopping");
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              task.run();
            } finally {
              permits.release();
            }
          });
    } catch (TaskRejectedException e) {
      permits.release();
      LOG.warn("A background task was dropped: the executor does not accept it");
    }
  }

  @Override
  public void start() {
    running.set(true);
  }

  /** Waits for the accepted tasks without interrupting them; new ones are dropped meanwhile. */
  @Override
  public void stop() {
    running.set(false);
    stopping.set(true);
    try {
      if (permits.tryAcquire(CONCURRENCY_LIMIT, TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        // Given back so that the close that follows the stop does not wait again.
        permits.release(CONCURRENCY_LIMIT);
      } else {
        LOG.warn("Shutdown does not wait more for the background tasks that are still running");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public void close() {
    stop();
    executor.close();
  }
}
