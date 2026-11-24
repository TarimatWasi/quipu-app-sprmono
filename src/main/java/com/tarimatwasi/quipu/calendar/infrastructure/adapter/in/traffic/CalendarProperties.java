package com.tarimatwasi.quipu.calendar.infrastructure.adapter.in.traffic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The business day.
 *
 * @param trafficTrigger whether the traffic starts the business day; off in the tests, which state
 *     their days themselves
 */
@Validated
@ConfigurationProperties("app.calendar")
public record CalendarProperties(boolean trafficTrigger) {}
