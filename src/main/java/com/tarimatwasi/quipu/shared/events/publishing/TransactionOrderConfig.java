package com.tarimatwasi.quipu.shared.events.publishing;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Replaces Boot's default so the transaction wraps {@link PublishEventAspect} (EventOrdering). */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = EventOrdering.TRANSACTION)
public class TransactionOrderConfig {}
