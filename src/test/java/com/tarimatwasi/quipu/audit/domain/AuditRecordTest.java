package com.tarimatwasi.quipu.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.fine.domain.event.FineApplied;
import com.tarimatwasi.quipu.shared.events.EventMetadata;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditRecordTest {

  @Test
  void of_shapesTheFactAsACloudEvent() {
    var metadata = new EventMetadata(UUID.randomUUID(), Instant.parse("2026-11-22T10:00:00Z"), 4L);
    var event =
        new FineApplied(metadata, 12L, 30L, new BigDecimal("15.00"), YearMonth.of(2026, 11));

    var entry = AuditRecord.of(event);

    assertThat(entry.id()).isEqualTo(metadata.eventId());
    assertThat(entry.source()).isEqualTo("/quipu/fine");
    assertThat(entry.type()).isEqualTo("com.tarimatwasi.quipu.fine.fine-applied.v1");
    assertThat(entry.subject()).isEqualTo("fine/12");
    assertThat(entry.time()).isEqualTo(metadata.occurredAt());
    assertThat(entry.actorUserId()).isEqualTo(4L);
    assertThat(entry.guestId()).isEqualTo(30L);
    assertThat(entry.event()).isSameAs(event);
  }
}
