package com.tarimatwasi.quipu.support;

import com.tarimatwasi.quipu.auth.port.out.BackgroundTaskPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Runs the background work of the request in the caller's thread, so an integration test can check
 * its effect right after the answer. The real adapter is covered by its own test.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SynchronousBackgroundTasksConfig {

  @Bean
  @Primary
  BackgroundTaskPort synchronousBackgroundTasks() {
    return Runnable::run;
  }
}
