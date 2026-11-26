package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.events.delivery.DurableListener;
import com.tarimatwasi.quipu.support.PostgresContainers;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * The plain {@code @Async} of the mail adapters runs on Spring Boot's applicationTaskExecutor (its
 * concurrency limit and rejection are what the mail adapters handle), never on the executor of the
 * domain events. Boot backs off from its executor when another Executor bean exists unless {@code
 * spring.task.execution.mode} is {@code force}; MailAsyncConfigTest builds an isolated context and
 * cannot see that, so this checks the full one.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class AsyncExecutorsContractTest {

  @Autowired ApplicationContext context;

  @Test
  void plainAsync_runsOnTheExecutorOfSpringBoot_notOnTheDomainEventOne() {
    assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
    // The executor of a plain @Async is the one the AsyncConfigurer names (Boot registers it).
    Executor plainAsync =
        Objects.requireNonNull(
            context.getBeanProvider(AsyncConfigurer.class).getObject().getAsyncExecutor());
    assertThat(plainAsync)
        .isSameAs(context.getBean("applicationTaskExecutor"))
        .isNotSameAs(context.getBean(DurableListener.EXECUTOR));
    assertThat(plainAsync)
        .isInstanceOfSatisfying(
            SimpleAsyncTaskExecutor.class,
            executor -> assertThat(executor.getConcurrencyLimit()).isEqualTo(10));
  }
}
