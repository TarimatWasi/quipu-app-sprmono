/**
 * Domain events: what a module states after a confirmed change, and how it states it ({@link
 * com.tarimatwasi.quipu.shared.events.PublishEvent}). Delivery goes through the Spring Modulith
 * outbox; consumers (audit, notifications, live invalidations) are listeners in their own modules.
 */
@NullMarked
@NamedInterface("events")
package com.tarimatwasi.quipu.shared.events;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
