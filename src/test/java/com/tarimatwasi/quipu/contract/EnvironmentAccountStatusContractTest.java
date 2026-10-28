package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * TAR-44 (RF-06): the administrator reads the status by period of the current contract of an
 * environment, classified and ordered as in the account status of a guest, against the shared
 * contract. The day is fixed at 2026-10-14 in Lima.
 */
class EnvironmentAccountStatusContractTest extends PaymentsContractSupport {

  private Long environment;

  private String url(Long environmentId) {
    return url(ids().mask(IdKind.ENVIRONMENT, environmentId));
  }

  private static String url(UUID environmentId) {
    return "/bff/admin/environments/" + environmentId + "/account-status";
  }

  @BeforeEach
  void anEnvironment() {
    environment = insertEnvironment("305", "ACTIVE");
  }

  @Test
  void theAdministratorReadsEveryPeriodOfTheCurrentContractOfTheEnvironmentInOrder()
      throws Exception {
    // An older contract of the same environment, finished by hand, with a payment of its own.
    Long previousGuest = insertGuest("11112222", "CONTRACT", "INACTIVE", "Luis Mamani");
    Long previous = insertContract(previousGuest, environment, "2025-01-01", null, 600, 5, 3);
    terminate(previous, "2025-12-31T15:00:00Z");
    insertPayment(previousGuest, previous, null, "2025-06", "APPROVED", "2025-06-02T10:00:00Z");
    // The current one: due on the 20th with 3 days of grace.
    Long current = insertContract(contractGuest, environment, "2026-07-01", null, 950, 20, 3);
    Long paid =
        insertPayment(contractGuest, current, null, "2026-08", "APPROVED", "2026-08-15T10:00:00Z");
    Long underReview =
        insertPayment(contractGuest, current, null, "2026-09", "PENDING", "2026-09-15T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(url(environment)).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].period").value("2026-07"))
            .andExpect(jsonPath("$[0].status").value("OVERDUE"))
            .andExpect(jsonPath("$[0].dueDate").value("2026-07-20"))
            .andExpect(jsonPath("$[1].period").value("2026-08"))
            .andExpect(jsonPath("$[1].status").value("PAID"))
            .andExpect(jsonPath("$[1].paymentId").value(id(IdKind.PAYMENT, paid)))
            .andExpect(jsonPath("$[2].period").value("2026-09"))
            .andExpect(jsonPath("$[2].status").value("UNDER_REVIEW"))
            .andExpect(jsonPath("$[2].paymentId").value(id(IdKind.PAYMENT, underReview)))
            .andExpect(jsonPath("$[3].period").value("2026-10"))
            .andExpect(jsonPath("$[3].status").value("UNPAID"))
            .andExpect(jsonPath("$[3].dueDate").value("2026-10-20"))
            .andReturn();

    List<Map<String, Object>> periods =
        JsonPath.read(result.getResponse().getContentAsString(), "$");
    String currentId = id(IdKind.CONTRACT, current);
    assertThat(periods)
        .allSatisfy(
            period ->
                assertThat(period)
                    .containsEntry("contractId", currentId)
                    .containsEntry("amount", 950.0));
    assertThat(periods.get(0))
        .containsEntry("paymentId", null)
        .containsEntry("rejectionReason", null);
    assertThat(periods.get(3)).containsEntry("paymentId", null);
  }

  @Test
  void thePeriodsAreTheOnesTheGuestViewShowsForThatContract() throws Exception {
    // The guest of the fixture holds room 201 since 2026-01-01; its view adds the other contract.
    Long current = insertContract(contractGuest, environment, "2026-07-01", null, 950, 20, 3);
    insertPayment(contractGuest, current, null, "2026-08", "REJECTED", "2026-08-15T10:00:00Z");

    List<Map<String, Object>> ofTheEnvironment = read(url(environment));
    List<Map<String, Object>> ofTheGuest =
        read("/bff/admin/guests/" + ids().mask(IdKind.GUEST, contractGuest) + "/account-status");

    String currentId = id(IdKind.CONTRACT, current);
    assertThat(ofTheGuest).hasSize(14);
    assertThat(ofTheEnvironment)
        .isEqualTo(ofTheGuest.stream().filter(p -> currentId.equals(p.get("contractId"))).toList());
    assertThat(ofTheEnvironment.get(1))
        .containsEntry("period", "2026-08")
        .containsEntry("status", "OVERDUE")
        .containsEntry("rejectionReason", "No coincide");
  }

  @Test
  void anInactiveEnvironmentStillHasThePeriodsOfItsCurrentContract() throws Exception {
    Long current = insertContract(contractGuest, environment, "2026-09-01", null, 950, 20, 3);
    jdbc.update("UPDATE environments SET status = 'INACTIVE' WHERE id = ?", environment);

    mockMvc
        .perform(get(url(environment)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].period").value("2026-09"))
        .andExpect(jsonPath("$[0].status").value("OVERDUE"))
        .andExpect(jsonPath("$[0].contractId").value(id(IdKind.CONTRACT, current)))
        .andExpect(jsonPath("$[1].period").value("2026-10"))
        .andExpect(jsonPath("$[1].status").value("UNPAID"));
  }

  @Test
  void anEnvironmentWithoutContractsAnswersAnEmptyList() throws Exception {
    assertThat(body(url(environment))).isEqualTo("[]");
  }

  @Test
  void anEnvironmentWhoseContractExpiredOrWasTerminatedAnswersAnEmptyList() throws Exception {
    Long expired =
        insertContract(contractGuest, environment, "2026-01-01", "2026-10-13", 950, 5, 3);
    insertPayment(contractGuest, expired, null, "2026-09", "APPROVED", "2026-09-02T10:00:00Z");
    Long other = insertEnvironment("306", "ACTIVE");
    Long terminated = insertContract(contractGuest, other, "2026-01-01", null, 950, 5, 3);
    terminate(terminated, "2026-10-01T15:00:00Z");
    insertPayment(contractGuest, terminated, null, "2026-08", "APPROVED", "2026-08-02T10:00:00Z");

    assertThat(body(url(environment))).isEqualTo("[]");
    assertThat(body(url(other))).isEqualTo("[]");
  }

  @Test
  void aContractThatEndsTodayIsStillCurrent() throws Exception {
    Long current =
        insertContract(contractGuest, environment, "2026-10-01", "2026-10-14", 950, 20, 3);

    mockMvc
        .perform(get(url(environment)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].period").value("2026-10"))
        .andExpect(jsonPath("$[0].contractId").value(id(IdKind.CONTRACT, current)));
  }

  @Test
  void anEnvironmentThatDoesNotExistIsNotFound() throws Exception {
    // A masked id of an environment that is not there, and one this service never issued.
    for (String missing : new String[] {url(TestIds.next()), url(UUID.randomUUID())}) {
      mockMvc
          .perform(get(missing).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("ENVIRONMENT_NOT_FOUND"));
    }
  }

  @Test
  void aGuestAndAnAnonymousRequestDoNotReachIt() throws Exception {
    insertContract(contractGuest, environment, "2026-07-01", null, 950, 20, 3);

    mockMvc
        .perform(get(url(environment)).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(url(environment)))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  // ------------------------------------------------------------------ helpers

  private String body(String url) throws Exception {
    return mockMvc
        .perform(get(url).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private List<Map<String, Object>> read(String url) throws Exception {
    return JsonPath.read(body(url), "$");
  }

  private Long insertEnvironment(String code, String status) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, 'ROOM', ?)",
        id,
        code,
        status);
    return id;
  }

  private Long insertContract(
      Long guest,
      Long environmentId,
      String start,
      @Nullable String end,
      int amount,
      int paymentDay,
      int graceDays) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?,"
            + " ?::date, ?::date, ?, ?, ?, 'contracts/a.pdf', 'a.pdf', 10)",
        id,
        guest,
        environmentId,
        start,
        end,
        amount,
        paymentDay,
        graceDays);
    return id;
  }

  private void terminate(Long contractId, String at) {
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = ?::timestamptz WHERE id = ?",
        at,
        contractId);
  }
}
