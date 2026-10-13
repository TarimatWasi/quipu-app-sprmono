package com.tarimatwasi.quipu.auth.adapter.out.async;

import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Hands the task to the task executor of Spring Boot (ADR-F4), so the answer of "forgot password"
 * does not depend on whether the account exists. When the executor is at its limit the task is
 * dropped with a warning; the person asks again once the cooldown passes.
 */
@Component
public class AsyncBackgroundTasks implements BackgroundTaskPort {

  private static final Logger LOG = LoggerFactory.getLogger(AsyncBackgroundTasks.class);

  private final Executor executor;

  public AsyncBackgroundTasks(Executor executor) {
    this.executor = executor;
  }

  @Override
  public void run(Runnable task) {
    try {
      executor.execute(task);
    } catch (TaskRejectedException e) {
      LOG.warn("A background task was dropped: the task executor is at its concurrency limit");
    }
  }

  /**
   * The {@code @Async} method is in its own bean so that the call goes through Spring's proxy. A
   * failure inside the task never reaches the caller: Spring's default handler for {@code @Async}
   * methods that return nothing logs it.
   */
  @Component
  public static class Executor {

    @Async
    public void execute(Runnable task) {
      task.run();
    }
  }
}
