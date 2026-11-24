package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.shared.events.delivery.AsyncFailureLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The domain event platform end to end: a real secured mutation publishes its fact inside the
 * business transaction; the outbox delivers it to the audit trail after the commit, shaped as a
 * CloudEvent; the live invalidation is not persisted in the outbox. Completed publications are kept
 * here (production deletes them) to see which listeners the outbox tracked.
 */
@TestPropertySource(properties = "spring.modulith.events.completion-mode=update")
class DomainEventsContractTest extends PaymentsContractSupport {
  private static final String EXPENSE_CREATED = "com.tarimatwasi.quipu.expense.expense-created.v1";
  private static final String EXPENSE_DELETED = "com.tarimatwasi.quipu.expense.expense-deleted.v1";

  @Autowired private AsyncConfigurer asyncFailures;

  private String createExpense() throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/bff/admin/expenses")
                    .with(csrf())
                    .cookie(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"category\":\"UTILITIES\",\"amount\":12.50,\"month\":\"2026-10\","
                            + "\"description\":\"Recibo de luz de Ana\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private Map<String, Object> awaitAudit(String type) {
    return Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> jdbc.queryForList("SELECT * FROM audit_event WHERE type = ?", type),
            rows -> rows.size() == 1)
        .getFirst();
  }

  @Test
  void createdExpense_isAuditedAsACloudEventWithItsActorAndNoFreeText() throws Exception {
    createExpense();

    Map<String, Object> entry = awaitAudit(EXPENSE_CREATED);
    Long expenseId = jdbc.queryForObject("SELECT id FROM expenses", Long.class);
    assertThat(entry)
        .containsEntry("spec_version", "1.0")
        .containsEntry("source", "/quipu/expense")
        .containsEntry("subject", "expense/" + expenseId)
        .containsEntry("actor_user_id", adminId);
    assertThat(entry.get("guest_id")).isNull();
    String data = entry.get("data").toString();
    assertThat(JsonPath.<Number>read(data, "$.expenseId").longValue()).isEqualTo(expenseId);
    assertThat(JsonPath.<String>read(data, "$.month")).isEqualTo("2026-10");
    assertThat(data).doesNotContain("Recibo", "metadata");
  }

  @Test
  void outbox_persistsOnlyTheDurableListenersAndCompletesThem() throws Exception {
    createExpense();
    awaitAudit(EXPENSE_CREATED);

    List<String> listeners =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    jdbc.queryForList(
                        "SELECT listener_id FROM event_publication WHERE completion_date IS NOT NULL"
                            + " AND event_type LIKE '%ExpenseCreated'",
                        String.class),
                rows -> !rows.isEmpty());
    assertThat(listeners)
        .singleElement()
        .satisfies(listener -> assertThat(listener).contains("AuditEventListener"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE listener_id LIKE '%Invalidations%'",
                Integer.class))
        .isZero();
  }

  /**
   * BE-SPR-OBS-06: Spring Modulith logs a failed durable listener at INFO with the message of its
   * exception; that line stays off, AsyncFailureLog writes the method and the class instead.
   */
  @Test
  void failedDeliveries_neverLogTheMessageOfTheirException() {
    assertThat(
            LoggerFactory.getLogger(
                    CompletionRegisteringAdvisor.class.getName()
                        + "$CompletionRegisteringMethodInterceptor")
                .isInfoEnabled())
        .isFalse();
    assertThat(asyncFailures).isInstanceOf(AsyncFailureLog.class);
  }

  @Test
  void deletedExpense_isAuditedOnce() throws Exception {
    String id = createExpense();
    awaitAudit(EXPENSE_CREATED);

    mockMvc
        .perform(
            MockMvcRequestBuilders.delete("/bff/admin/expenses/" + id).with(csrf()).cookie(admin()))
        .andExpect(status().isNoContent());

    assertThat(awaitAudit(EXPENSE_DELETED)).containsEntry("actor_user_id", adminId);
  }

  @Test
  void rejectedMutation_publishesNothing() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/bff/admin/expenses")
                .with(csrf())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"UTILITIES\",\"amount\":-1,\"month\":\"2026-10\"}"))
        .andExpect(status().is4xxClientError());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class))
        .isZero();
  }
}
