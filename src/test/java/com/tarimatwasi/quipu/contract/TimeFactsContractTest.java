package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.calendar.application.port.in.StartBusinessDayUseCase;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The facts of time end to end: a business day starts, every module sweeps what time alone made
 * true through the outbox, and the bell gets one notification per fact. Starting the same day again
 * states nothing new (the ids name the day and the charge).
 */
class TimeFactsContractTest extends PaymentsContractSupport {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

  @Autowired StartBusinessDayUseCase calendar;

  /** A guest whose October rent is due since the 10th (10 days of grace) and ends on Nov 1st. */
  private void endingContractDueToday() {
    Long guest = insertGuest("44445555", "CONTRACT", "ACTIVE", "Luis Mamani");
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '305', 'ROOM', 'ACTIVE')",
        room);
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?,"
            + " '2026-10-01', '2026-11-01', 600, 10, 10, 'contracts/b.pdf', 'b.pdf', 10)",
        TestIds.next(),
        guest,
        room);
  }

  /** A temporary guest whose stay ends tomorrow, and a guest whose access expired unused. */
  private void stayEndingAndAccessExpired() {
    Long temporary = insertGuest("66667777", "TEMPORARY", "ACTIVE", null);
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-10-10', '2026-10-15', 90)",
        TestIds.next(),
        temporary);
    Long pending = insertGuest("88889999", "CONTRACT", "PENDING_ACTIVATION", null);
    jdbc.update(
        "INSERT INTO access_grants (id, guest_id, mode, token_hash, expires_at)"
            + " VALUES (?, ?, 'LINK', ?, '2026-10-13T00:00:00Z')",
        TestIds.next(),
        pending,
        "a".repeat(64));
  }

  /** What the home of the administrator counts as overdue: the same book the sweep reads. */
  private long overdueOnTheDashboard() throws Exception {
    String body =
        mockMvc
            .perform(MockMvcRequestBuilders.get("/bff/admin/dashboard").cookie(admin()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.<Number>read(body, "$.overdue.count").longValue();
  }

  private Map<String, Long> notificationsByType() {
    return jdbc.queryForList("SELECT type FROM notifications", String.class).stream()
        .collect(Collectors.groupingBy(type -> type, Collectors.counting()));
  }

  private Map<String, Long> awaitNotifications(int total) {
    return Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            this::notificationsByType,
            found -> found.values().stream().mapToLong(Long::longValue).sum() == total);
  }

  private void awaitOutboxDrained() {
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE completion_date IS NULL",
                    Integer.class),
            pending -> pending != null && pending == 0);
  }

  @Test
  void businessDay_statesDuesArrearsAndEndingContractsOnce() throws Exception {
    endingContractDueToday();
    stayEndingAndAccessExpired();
    long overdue = overdueOnTheDashboard();

    calendar.start(DAY);

    // A fact of time never disagrees with the screens: one per overdue charge the home counts,
    // plus the new contract (due today, ending within RN-07), the stay ending tomorrow and the
    // access expired unused.
    Map<String, Long> found = awaitNotifications((int) overdue + 4);
    assertThat(overdue).isPositive();
    assertThat(found)
        .containsEntry("PAYMENT_OVERDUE", overdue)
        .containsEntry("PAYMENT_DUE", 1L)
        .containsEntry("CONTRACT_EXPIRING", 1L)
        .containsEntry("STAY_ENDING", 1L)
        .containsEntry("ACCESS_EXPIRED", 1L);

    calendar.start(DAY);
    awaitOutboxDrained();

    assertThat(notificationsByType()).isEqualTo(found);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE type LIKE '%business-day-started%'",
                Integer.class))
        .isOne();
  }

  @Test
  void dueNotification_readsItsAmountMonthAndDate() throws Exception {
    endingContractDueToday();
    long overdue = overdueOnTheDashboard();

    calendar.start(DAY);

    awaitNotifications((int) overdue + 2);
    String facts =
        jdbc.queryForObject(
            "SELECT facts::text FROM notifications WHERE type = 'PAYMENT_DUE'", String.class);
    assertThat(facts).contains("600", "2026-10", "2026-10-10");
  }
}
