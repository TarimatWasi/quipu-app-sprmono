package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on Spring's {@code @Async} for the mail adapters (ADR-F4). The executor is the one Spring
 * Boot configures from {@code spring.threads.virtual.enabled} and {@code spring.task.execution.*}
 * in application.yml; there is no executor of our own.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class MailAsyncConfig {}
