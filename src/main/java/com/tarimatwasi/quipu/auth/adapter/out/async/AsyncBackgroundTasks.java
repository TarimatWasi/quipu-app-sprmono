package com.tarimatwasi.quipu.auth.adapter.out.async;

import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * Runs the task in a virtual thread of its own executor (ADR-F4), so the answer of "forgot
 * password" does not depend on whether the account exists. The executor is not the shared {@code
 * applicationTaskExecutor}: the task itself submits the recovery email to that one, and sharing the
 * limit would let the outer tasks starve their own emails. When this executor is at its limit the
 * task is dropped with a warning; the person asks again once the cooldown passes.
 */
@Component
public class AsyncBackgroundTasks implements BackgroundTaskPort, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncBackgroundTasks.class);

  private static final int CONCURRENCY_LIMIT = 10;
  private static final long TERMINATION_TIMEOUT_MS = 8_000;

  private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("background-");

  public AsyncBackgroundTasks() {
    executor.setVirtualThreads(true);
    executor.setConcurrencyLimit(CONCURRENCY_LIMIT);
    executor.setRejectTasksWhenLimitReached(true);
    executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MS);
  }

  /**
   * A failure inside the task never reaches the caller: it ends in the thread's uncaught-exception
   * handler, which logs it.
   */
  @Override
  public void run(Runnable task) {
    try {
      executor.execute(task);
    } catch (TaskRejectedException e) {
      LOG.warn("A background task was dropped: the executor is at its concurrency limit");
    }
  }

  @Override
  public void close() {
    executor.close();
  }
}
