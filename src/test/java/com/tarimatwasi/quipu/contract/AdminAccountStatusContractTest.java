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
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * TAR-44 (RF-06): the administrator reads the status by period of any guest, the same
 * classification that the guest sees in its own account status, against the shared contract.
 */
class AdminAccountStatusContractTest extends PaymentsContractSupport {

  private static final String OWN = "/bff/guest/account-status";

  private String url(Long guestId) {
    return url(masker.mask(IdKind.GUEST, guestId));
  }

  private static String url(UUID guestId) {
    return "/bff/admin/guests/" + guestId + "/account-status";
  }

  @Test
  void theAdministratorReadsThePeriodsOfAGuestWithAContractAsTheGuestSeesThem() throws Exception {
    Long approved =
        insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-09-02T10:00:00Z");
    Long rejected =
        insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-02T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(url(contractGuest)).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].period").value("2026-01"))
            .andExpect(jsonPath("$[0].status").value("OVERDUE"))
            .andExpect(jsonPath("$[0].dueDate").value("2026-01-05"))
            .andExpect(jsonPath("$[0].amount").value(800))
            .andExpect(jsonPath("$[0].contractId").value(id(IdKind.CONTRACT, contract)))
            .andExpect(jsonPath("$[8].period").value("2026-09"))
            .andExpect(jsonPath("$[8].status").value("PAID"))
            .andExpect(jsonPath("$[8].paymentId").value(id(IdKind.PAYMENT, approved)))
            .andExpect(jsonPath("$[9].period").value("2026-10"))
            .andExpect(jsonPath("$[9].status").value("OVERDUE"))
            .andExpect(jsonPath("$[9].paymentId").value(id(IdKind.PAYMENT, rejected)))
            .andExpect(jsonPath("$[9].rejectionReason").value("No coincide"))
            .andReturn();

    List<Map<String, Object>> periods =
        JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(periods.get(0))
        .containsEntry("paymentId", null)
        .containsEntry("rejectionReason", null);
    String own =
        mockMvc
            .perform(get(OWN).cookie(guest()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(result.getResponse().getContentAsString()).isEqualTo(own);
  }

  @Test
  void anInactiveGuestStillHasItsPeriods() throws Exception {
    jdbc.update("UPDATE guests SET status = 'INACTIVE' WHERE id = ?", contractGuest);

    mockMvc
        .perform(get(url(contractGuest)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[9].status").value("OVERDUE"));
  }

  @Test
  void aTemporaryGuestHasNoContractsAndAnswersAnEmptyList() throws Exception {
    insertPayment(temporaryGuest, null, stay, null, "APPROVED", "2026-10-05T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(url(temporaryGuest)).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
  }

  @Test
  void aGuestThatDoesNotExistIsNotFound() throws Exception {
    // A masked id of a guest that is not there, and one this service never issued.
    for (String missing : new String[] {url(TestIds.next()), url(UUID.randomUUID())}) {
      mockMvc
          .perform(get(missing).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    }
  }

  @Test
  void aGuestAndAnAnonymousRequestDoNotReachIt() throws Exception {
    mockMvc
        .perform(get(url(contractGuest)).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(url(contractGuest)))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }
}
