/**
 * How durable listeners receive domain events: through the outbox, on an executor of their own, so
 * a burst of facts (the sweep of a business day) waits in a queue instead of being rejected by the
 * shared executor, which the mail adapters keep at its limit on purpose.
 */
@NullMarked
@NamedInterface("event-delivery")
package com.tarimatwasi.quipu.shared.events.delivery;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
