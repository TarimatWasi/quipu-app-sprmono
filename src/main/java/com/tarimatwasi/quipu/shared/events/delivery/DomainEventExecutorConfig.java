package com.tarimatwasi.quipu.shared.events.delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor of {@link DurableListener}: few deliveries at once, so the requests keep most of the
 * connection pool (10), and a queue for bursts. A full queue rejects; the outbox keeps the delivery
 * and OutboxRecovery or the restart resubmits it.
 */
@Configuration(proxyBeanMethods = false)
public class DomainEventExecutorConfig {

  /** Deliveries running at once. */
  static final int CONCURRENT = 4;

  /** Deliveries waiting their turn. */
  static final int QUEUED = 1000;

  @Bean(name = DurableListener.EXECUTOR)
  ThreadPoolTaskExecutor domainEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(CONCURRENT);
    executor.setMaxPoolSize(CONCURRENT);
    executor.setQueueCapacity(QUEUED);
    executor.setVirtualThreads(true);
    executor.setThreadNamePrefix("domain-event-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(8);
    return executor;
  }
}
