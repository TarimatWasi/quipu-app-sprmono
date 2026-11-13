package com.tarimatwasi.quipu.contract;

import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamRegistry;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.SseFrames;
import com.tarimatwasi.quipu.support.TestIds;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/** TAR-376: real secured mutations, database transactions and SSE resource contracts. */
@TestPropertySource(properties = {"app.changes.heartbeat=1ms", "app.changes.max-duration=10m"})
class ExpandedChangesContractTest extends PaymentsContractSupport {
  @Autowired ChangeStreamRegistry streams;
  @Autowired Clock clock;
  @Autowired PlatformTransactionManager transactions;

  private ChangeStreamRegistry.Subscription adminStream() {
    var subscription =
        streams.reserve(adminId, "admin", 0, true, ids(), clock.instant().plusSeconds(300));
    subscription.activate(null);
    return subscription;
  }

  private ChangeStreamRegistry.Subscription guestStream(Long owner) {
    var subscription =
        streams.reserve(owner, "guest", 0, false, ids(), clock.instant().plusSeconds(300));
    subscription.activate(owner);
    return subscription;
  }

  private String expense() throws Exception {
    String body =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/bff/admin/expenses")
                    .with(csrf())
                    .cookie(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"category\":\"UTILITIES\",\"amount\":12,\"month\":\"2026-10\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void ownProfileEdit_updatesItsReadersWithoutNotifyingAnotherGuest() throws Exception {
    try (var admin = adminStream();
        var own = guestStream(contractGuest);
        var other = guestStream(temporaryGuest)) {
      mockMvc
          .perform(
              patch("/bff/guest/profile")
                  .with(csrf())
                  .cookie(guest())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"phone\":\"977000111\"}"))
          .andExpect(status().isOk());
      String frame = admin.next();
      assertThat(frame).contains("admin.guests", "admin.guest:" + id(IdKind.GUEST, contractGuest));
      SseFrames.assertValid(frame, "Admin");
      String guestFrame = own.next();
      assertThat(guestFrame).contains("guest.profile").doesNotContain("admin.");
      SseFrames.assertValid(guestFrame, "Guest");
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void nameEdit_refreshesVisibleHistoricalLabelsOnlyForItsOwner() throws Exception {
    try (var admin = adminStream();
        var own = guestStream(contractGuest);
        var other = guestStream(temporaryGuest)) {
      mockMvc
          .perform(
              patch("/bff/guest/profile")
                  .with(csrf())
                  .cookie(guest())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"Nombre de prueba\"}"))
          .andExpect(status().isOk());
      assertThat(admin.next()).contains("\"invalidate\":\"ALL\"");
      String frame = own.next();
      assertThat(frame).contains("\"invalidate\":\"ALL\"").doesNotContain("Nombre");
      SseFrames.assertValid(frame, "Guest");
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void occupiedEnvironmentRename_refreshesOnlyItsHistoricalOccupants() throws Exception {
    Long environment =
        jdbc.queryForObject(
            "SELECT environment_id FROM contracts WHERE id=?", Long.class, contract);
    try (var admin = adminStream();
        var own = guestStream(contractGuest);
        var other = guestStream(temporaryGuest)) {
      mockMvc
          .perform(
              patch("/bff/admin/environments/" + id(IdKind.ENVIRONMENT, environment))
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"code\":\"Renamed-376\"}"))
          .andExpect(status().isOk());
      assertThat(admin.next()).contains("\"invalidate\":\"ALL\"");
      assertThat(own.next()).contains("\"invalidate\":\"ALL\"").doesNotContain("Renamed");
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void cancelledBeforeStartContractOwner_stillReceivesEnvironmentLabelInvalidation()
      throws Exception {
    jdbc.update(
        "UPDATE contracts SET start_date='2026-12-01', status='TERMINATED',"
            + " terminated_at='2026-10-01T12:00:00Z' WHERE id=?",
        contract);
    Long environment =
        jdbc.queryForObject(
            "SELECT environment_id FROM contracts WHERE id=?", Long.class, contract);
    try (var admin = adminStream();
        var own = guestStream(contractGuest);
        var other = guestStream(temporaryGuest)) {
      mockMvc
          .perform(
              patch("/bff/admin/environments/" + id(IdKind.ENVIRONMENT, environment))
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"code\":\"Cancelled-376\"}"))
          .andExpect(status().isOk());
      assertThat(admin.next()).contains("\"invalidate\":\"ALL\"");
      assertThat(own.next()).contains("\"invalidate\":\"ALL\"");
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void expenseLifecycle_invalidatesOnlyAdministrativeFinance() throws Exception {
    try (var admin = adminStream();
        var own = guestStream(contractGuest)) {
      String expense = expense();
      assertThat(admin.next()).contains("admin.finance").doesNotContain("admin.dashboard");
      mockMvc
          .perform(
              patch("/bff/admin/expenses/" + expense)
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"category\":\"UTILITIES\",\"amount\":15,\"month\":\"2026-10\",\"version\":0}"))
          .andExpect(status().isOk());
      assertThat(admin.next()).contains("admin.finance");
      mockMvc
          .perform(delete("/bff/admin/expenses/" + expense).with(csrf()).cookie(admin()))
          .andExpect(status().isNoContent());
      assertThat(admin.next()).contains("admin.finance");
      assertThat(own.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void fineLifecycle_keepsOwnerForDeletionAndDoesNotNotifyGuest() throws Exception {
    try (var admin = adminStream();
        var own = guestStream(contractGuest)) {
      String body =
          mockMvc
              .perform(
                  MockMvcRequestBuilders.post("/bff/admin/fines")
                      .with(csrf())
                      .cookie(admin())
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"guestId\":\""
                              + id(IdKind.GUEST, contractGuest)
                              + "\",\"amount\":10,\"month\":\"2026-10\"}"))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(admin.next())
          .contains("admin.finance", "admin.guest:" + id(IdKind.GUEST, contractGuest));
      mockMvc
          .perform(
              delete("/bff/admin/fines/" + JsonPath.<String>read(body, "$.id"))
                  .with(csrf())
                  .cookie(admin()))
          .andExpect(status().isNoContent());
      assertThat(admin.next())
          .contains("admin.finance", "admin.guest:" + id(IdKind.GUEST, contractGuest));
      assertThat(own.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void stayEdit_invalidatesDerivedOccupancyDashboardAndFinance() throws Exception {
    Long payment =
        insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-14T12:00:00Z");
    String paymentUrl = "/bff/admin/payments/" + id(IdKind.PAYMENT, payment);
    mockMvc
        .perform(get(paymentUrl).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expectedAmount").value(150));
    try (var admin = adminStream();
        var own = guestStream(temporaryGuest);
        var other = guestStream(contractGuest)) {
      mockMvc
          .perform(
              patch(
                      "/bff/admin/guests/"
                          + id(IdKind.GUEST, temporaryGuest)
                          + "/stays/"
                          + id(IdKind.STAY, stay))
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"agreedAmount\":180}"))
          .andExpect(status().isOk());
      String frame = admin.next();
      assertThat(frame).contains("\"invalidate\":\"ALL\"");
      SseFrames.assertValid(frame, "Admin");
      assertThat(own.next()).contains("\"invalidate\":\"ALL\"");
      mockMvc
          .perform(get(paymentUrl).cookie(admin()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.expectedAmount").value(180))
          .andExpect(jsonPath("$.amountDue").value(180));
      assertThat(other.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void rolledBackMutation_emitsNothingAndLeavesNoExpense() throws Exception {
    try (var admin = adminStream()) {
      var tx =
          transactions.getTransaction(
              new org.springframework.transaction.support.DefaultTransactionDefinition());
      try {
        expense();
      } finally {
        transactions.rollback(tx);
      }
      assertThat(admin.next()).isEqualTo(": heartbeat\n\n");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses", Long.class)).isZero();
    }
  }

  @Test
  void environmentLifecycle_invalidatesListAndDetailWithoutGuestSignal() throws Exception {
    Long environment = TestIds.next();
    jdbc.update(
        "INSERT INTO environments(id,code,type,status) VALUES (?, 'test-376','ROOM','ACTIVE')",
        environment);
    String url = "/bff/admin/environments/" + id(IdKind.ENVIRONMENT, environment);
    try (var admin = adminStream();
        var own = guestStream(contractGuest)) {
      mockMvc
          .perform(
              patch(url)
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"version\":0,\"code\":\"test-376-edited\"}"))
          .andExpect(status().isOk());
      assertThat(admin.next()).contains("\"invalidate\":\"ALL\"");
      mockMvc
          .perform(MockMvcRequestBuilders.post(url + "/deactivate").with(csrf()).cookie(admin()))
          .andExpect(status().isNoContent());
      assertThat(admin.next()).contains("admin.environments");
      mockMvc
          .perform(MockMvcRequestBuilders.post(url + "/reactivate").with(csrf()).cookie(admin()))
          .andExpect(status().isNoContent());
      assertThat(admin.next()).contains("admin.environments");
      assertThat(own.next()).isEqualTo(": heartbeat\n\n");
    }
  }
}
