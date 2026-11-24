package com.tarimatwasi.quipu.shared.events.delivery;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * What a failed {@code @Async} method leaves in the log (BE-SPR-CON-05, BE-SPR-OBS-06): the method
 * and the class of the exception, never its message, since a database error carries the values of
 * the row. A {@link DurableListener} that fails stays pending in the outbox and is retried, so the
 * failure is a recoverable degradation (WARN). Spring Modulith's own INFO line with the message is
 * turned off in application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class AsyncFailureLog implements AsyncConfigurer {
  private static final Logger LOGGER = LoggerFactory.getLogger(AsyncFailureLog.class);

  @Override
  public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
    return AsyncFailureLog::failed;
  }

  static void failed(Throwable failure, Method method, Object... arguments) {
    LOGGER.warn(
        "An asynchronous call failed: {}.{} ({})",
        method.getDeclaringClass().getSimpleName(),
        method.getName(),
        failure.getClass().getName());
  }
}
