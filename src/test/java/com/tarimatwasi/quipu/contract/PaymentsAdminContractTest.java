package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * TAR-43: the queue, the detail and the review of the payments (RF-05, CU-05) against the shared
 * contract.
 */
class PaymentsAdminContractTest extends PaymentsContractSupport {

  @Test
  void theQueueIsOldestFirstWithTheGuestTheEnvironmentAndNoAmount() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-02T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(URL).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].guestName").value("Ana Quispe"))
            .andExpect(jsonPath("$.content[0].documentNumber").value("12345678"))
            .andExpect(jsonPath("$.content[0].guestType").value("CONTRACT"))
            .andExpect(jsonPath("$.content[0].environmentCode").value("201"))
            .andExpect(jsonPath("$.content[0].period").value("2026-09"))
            .andExpect(jsonPath("$.content[0].amount").doesNotExist())
            .andExpect(jsonPath("$.content[1].guestType").value("TEMPORARY"))
            .andReturn();
    // The temporary guest has no environment, no period and, until the onboarding, no name: all
    // of them in the answer as an explicit null, because the contract requires the fields.
    Map<String, Object> temporary =
        JsonPath.read(result.getResponse().getContentAsString(), "$.content[1]");
    assertThat(temporary)
        .containsEntry("environmentCode", null)
        .containsEntry("period", null)
        .containsEntry("guestName", null);
  }

  @Test
  void theQueueFiltersByStatusAndPages() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-07", "PENDING", "2026-10-01T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-08", "APPROVED", "2026-10-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-03T10:00:00Z");

    mockMvc
        .perform(get(URL).param("status", "APPROVED").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
    var first =
        mockMvc
            .perform(get(URL).param("status", "PENDING").param("limit", "1").cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content[0].period").value("2026-07"))
            .andReturn();
    String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
    mockMvc
        .perform(
            get(URL)
                .param("status", "PENDING")
                .param("limit", "1")
                .param("after", cursor)
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content[0].period").value("2026-09"))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  void aBadFilterOrPageParameterIsAValidationError() throws Exception {
    mockMvc
        .perform(get(URL).param("status", "SOON").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("status"));
    mockMvc
        .perform(get(URL).param("after", "nope").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("after"));
    mockMvc
        .perform(get(URL).param("limit", "0").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("limit"));
  }

  @Test
  void theDetailSignsTheVoucherWhenItIsRequestedAndIsNeverCached() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    mockMvc
        .perform(get(URL + "/" + masker.mask(IdKind.PAYMENT, id)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.voucherUrl").value(SIGNED))
        .andExpect(jsonPath("$.amount").value(800))
        .andExpect(
            jsonPath("$.contractId").value(masker.mask(IdKind.CONTRACT, contract).toString()))
        .andExpect(jsonPath("$.period").value("2026-09"));
    verify(files).signedUrl(KEY + "-" + id, VoucherType.PDF);
  }

  @Test
  void aPaymentOfAStayHasExplicitNullsForTheContractAndThePeriod() throws Exception {
    Long id = insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-01T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(URL + "/" + masker.mask(IdKind.PAYMENT, id)).cookie(admin()))
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.stayId").value(masker.mask(IdKind.STAY, stay).toString()))
            .andReturn();
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("contractId", null).containsEntry("period", null);
  }

  @Test
  void anUnknownPaymentIsNotFoundWhetherTheIdIsForgedOrMissing() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    jdbc.update("DELETE FROM payments WHERE id = ?", id);

    for (UUID unknown :
        new UUID[] {
          masker.mask(IdKind.PAYMENT, id), UUID.randomUUID(), masker.mask(IdKind.CONTRACT, id)
        }) {
      mockMvc
          .perform(get(URL + "/" + unknown).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
    mockMvc.perform(get(URL + "/not-a-uuid").cookie(admin())).andExpect(status().isBadRequest());
  }

  @Test
  void approvingResolvesItWithTheReviewerOfTheSession() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.voucherUrl").doesNotExist())
        .andExpect(jsonPath("$.rejectionReason").doesNotExist());

    assertThat(
            jdbc.queryForObject("SELECT reviewed_by_id FROM payments WHERE id = ?", Long.class, id))
        .isEqualTo(adminId);
    assertThat(
            jdbc.queryForObject(
                    "SELECT reviewed_at FROM payments WHERE id = ?", java.sql.Timestamp.class, id)
                .toInstant())
        .isEqualTo(Instant.parse("2026-10-14T14:00:00Z"));
  }

  @Test
  void rejectingKeepsTheReasonAndLeavesThePeriodOpenForAnotherVoucher() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"  El monto no coincide  \"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("El monto no coincide"));

    // the period is open again: a new voucher can be pending for it
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-05T10:00:00Z");
  }

  @Test
  void aRejectionNeedsAReasonThatIsNotBlankAndNotTooLong() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    for (String body :
        new String[] {
          "{\"status\":\"REJECTED\"}",
          "{\"status\":\"REJECTED\",\"reason\":\"\"}",
          "{\"status\":\"REJECTED\",\"reason\":\"   \"}",
          "{\"status\":\"REJECTED\",\"reason\":\"\\u00A0\"}",
          "{\"status\":\"REJECTED\",\"reason\":null}"
        }) {
      resolve(id, body)
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("PAYMENT_REASON_REQUIRED"));
    }
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"" + "x".repeat(501) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("reason"));
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"mal\\u0000motivo\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("reason"));
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void theBodyIsClosedAndTheReviewerCannotBeNamedInIt() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    resolve(id, "{\"status\":\"APPROVED\",\"reviewedById\":1}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("reviewedById"));
    resolve(id, "{\"status\":\"PENDING\"}").andExpect(status().isBadRequest());
    resolve(id, "{}").andExpect(status().isBadRequest());
    resolve(id, "not json").andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
  }

  @Test
  void aPaymentIsResolvedOnlyOnce() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    resolve(id, "{\"status\":\"APPROVED\"}").andExpect(status().isOk());

    resolve(id, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_RESOLVED"));
    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"Tarde\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_RESOLVED"));
  }

  /**
   * TAR-204: the contract is read again, locked, when the voucher is approved. A period it no
   * longer charges is a 409 PAYMENT_PERIOD_NOT_PAYABLE (TAR-207, contract v0.9.2) and the payment
   * stays pending; rejecting it still works.
   */
  @Test
  void approvingAPeriodTheContractNoLongerChargesIsAConflictAndThePaymentStaysPending()
      throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = '2026-08-20T15:00:00Z'"
            + " WHERE id = ?",
        contract);

    resolve(id, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_PERIOD_NOT_PAYABLE"))
        .andExpect(jsonPath("$.field").doesNotExist());
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");

    resolve(id, "{\"status\":\"REJECTED\",\"reason\":\"Contrato finalizado\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  /** A month owed of a contract finished later is still approved (D-31); a stay has no contract. */
  @Test
  void approvingAMonthOwedOfAFinishedContractOrAStayStillWorks() throws Exception {
    Long period =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    Long ofStay =
        insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-02T10:00:00Z");
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = '2026-10-12T15:00:00Z'"
            + " WHERE id = ?",
        contract);

    resolve(period, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    resolve(ofStay, "{\"status\":\"APPROVED\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void resolvingAnUnknownPaymentIsNotFound() throws Exception {
    mockMvc
        .perform(
            patch(URL + "/" + UUID.randomUUID())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract());
  }

  @Test
  void theQueueIsNeverCachedBecauseItCarriesDocumentNumbers() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");

    mockMvc
        .perform(get(URL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
