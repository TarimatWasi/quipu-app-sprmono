package com.tarimatwasi.quipu.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * TAR-240, TAR-239 part 2 (RN-38, RN-39): the credit derived from what an approved payment verified
 * over what it owed, the refunds of the credit that has nowhere to be used, and how the guest and
 * the administrator see both. The day is 2026-10-14. Besides the fixture, Luis has a contract of
 * September and October in the room 202, both paid, October with 120 too much; the temporary guest
 * paid its stay of 150 with 200.
 */
class RefundsContractTest extends PaymentsContractSupport {

  private static final String REFUNDS = "/bff/admin/refunds";
  private static final String CREDITS = "/bff/admin/credits";

  private final Long luisAccount = TestIds.next();
  private final Long temporaryAccount = TestIds.next();
  private Long luis;
  private Long luisOctober;
  private Long ofStay;

  @BeforeEach
  void credits() {
    luis = insertGuest("44556677", "CONTRACT", "ACTIVE", "Luis Mamani");
    insertAccount(luisAccount, "GUEST", "00000004", luis);
    insertAccount(temporaryAccount, "GUEST", "00000003", temporaryGuest);
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '202', 'ROOM', 'ACTIVE')",
        room);
    Long ended = insertContract(luis, room, "2026-09-01", "2026-10-31");
    approved(luis, ended, null, "2026-09", "800");
    luisOctober = approved(luis, ended, null, "2026-10", "920");
    ofStay = approved(temporaryGuest, null, stay, null, "200");
  }

  private Cookie luis() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(luisAccount.toString(), sessionId));
  }

  private Cookie temporary() {
    return new Cookie(
        "sessionToken", jwtTokenProvider.renew(temporaryAccount.toString(), sessionId));
  }

  /** An approved payment that verified that amount. */
  private Long approved(
      Long guest,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable String period,
      String verified) {
    Long id = insertPayment(guest, contractId, stayId, period, "APPROVED", "2026-10-02T15:00:00Z");
    jdbc.update(
        "UPDATE payments SET amount = ?::numeric, verified_amount = ?::numeric WHERE id = ?",
        verified,
        verified,
        id);
    jdbc.update(
        "UPDATE payment_proofs SET declared_amount = ?::numeric, verified_amount = ?::numeric"
            + " WHERE payment_id = ?",
        verified,
        verified,
        id);
    return id;
  }

  private ResultActions refund(Long guest, String amount, String date, String method)
      throws Exception {
    return refund(
        "{\"guestId\":\""
            + id(IdKind.GUEST, guest)
            + "\",\"amount\":"
            + amount
            + ",\"date\":\""
            + date
            + "\",\"method\":\""
            + method
            + "\"}");
  }

  private ResultActions refund(String body) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post(REFUNDS)
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void theCreditsToRefundGoOldestFirstAndARefundTakesThemDown() throws Exception {
    mockMvc
        .perform(get(CREDITS).param("pending", "true").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].guestId").value(id(IdKind.GUEST, luis)))
        .andExpect(jsonPath("$[0].guestName").value("Luis Mamani"))
        .andExpect(jsonPath("$[0].guestType").value("CONTRACT"))
        .andExpect(jsonPath("$[0].environmentCode").value("202"))
        .andExpect(jsonPath("$[0].amount").value(120))
        .andExpect(jsonPath("$[0].origin.paymentId").value(id(IdKind.PAYMENT, luisOctober)))
        .andExpect(jsonPath("$[0].origin.period").value("2026-10"))
        .andExpect(jsonPath("$[0].origin.contractEndedOn").value("2026-10-31"))
        .andExpect(jsonPath("$[1].guestType").value("TEMPORARY"))
        .andExpect(jsonPath("$[1].amount").value(50))
        .andExpect(jsonPath("$[1].environmentCode").isEmpty())
        .andExpect(jsonPath("$[1].origin.stayStartDate").value("2026-10-01"));

    refund(luis, "50", "2026-10-14", " Yape ")
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.guestName").value("Luis Mamani"))
        .andExpect(jsonPath("$.environmentCode").value("202"))
        .andExpect(jsonPath("$.method").value("Yape"))
        .andExpect(jsonPath("$.note").isEmpty());
    // RN-38: 70 are left, so 80 is more than the pending credit
    refund(luis, "80", "2026-10-14", "Yape")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("amount"));

    mockMvc
        .perform(get(CREDITS).param("pending", "true").cookie(admin()))
        .andExpect(jsonPath("$[0].amount").value(70));
    mockMvc
        .perform(get(REFUNDS).param("month", "2026-10").cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.content[0].amount").value(50))
        .andExpect(jsonPath("$.nextCursor").isEmpty());
    mockMvc
        .perform(get(REFUNDS).param("month", "2026-09").cookie(admin()))
        .andExpect(jsonPath("$.totalCount").value(0));
    // RN-39: the refund is taken out of the income of the month of its date
    mockMvc
        .perform(get("/bff/admin/finance").param("month", "2026-10").cookie(admin()))
        .andExpect(jsonPath("$.refundsTotal").value(50.00))
        .andExpect(jsonPath("$.refundsCount").value(1));
  }

  @Test
  void aRefundOutOfRangeIsAValidationErrorOfItsField() throws Exception {
    refund(luis, "10", "2026-10-15", "Yape")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.field").value("date"));
    refund(luis, "10", "2026-10-14", "   ")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.field").value("method"));
    refund(luis, "10.001", "2026-10-14", "Yape")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.field").value("amount"));
    // The fixture guest has no credit to refund
    refund(contractGuest, "10", "2026-10-14", "Yape")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("amount"));
    refund(
            "{\"guestId\":\""
                + id(IdKind.GUEST, TestIds.next())
                + "\",\"amount\":10,\"date\":\"2026-10-14\",\"method\":\"Yape\"}")
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mockMvc
        .perform(get(CREDITS).param("pending", "false").cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("pending"));
    mockMvc
        .perform(get(REFUNDS).param("month", "2026-10").cookie(guest()))
        .andExpect(status().isForbidden());
  }

  /** H1, H4: the guest whose contract ended sees what is refunded to it. */
  @Test
  void theGuestSeesItsCreditToRefundAndTheRefundInItsHistory() throws Exception {
    mockMvc
        .perform(get("/bff/guest/credit").cookie(luis()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.balance").value(120))
        .andExpect(jsonPath("$.toRefund").value(true))
        .andExpect(jsonPath("$.lastRefund").isEmpty());
    refund(luis, "120", "2026-10-14", "Yape").andExpect(status().isCreated());

    mockMvc
        .perform(get("/bff/guest/credit").cookie(luis()))
        .andExpect(jsonPath("$.balance").value(0))
        .andExpect(jsonPath("$.toRefund").value(false))
        .andExpect(jsonPath("$.lastRefund.amount").value(120))
        .andExpect(jsonPath("$.lastRefund.method").value("Yape"));
    mockMvc
        .perform(get("/bff/guest/payments").cookie(luis()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.creditBalance").value(0))
        .andExpect(jsonPath("$.content[0].kind").value("REFUND"))
        .andExpect(jsonPath("$.content[0].refund.amount").value(120))
        .andExpect(jsonPath("$.content[0].refund.fromPeriod").value("2026-10"))
        .andExpect(
            jsonPath("$.content[0].refund.fromPaymentId").value(id(IdKind.PAYMENT, luisOctober)))
        .andExpect(
            jsonPath("$.content[?(@.payment.period == '2026-10')].payment.surplus.outcome")
                .value("REFUNDED"));
  }

  /** RN-38: what September left over is discounted from October, which is what is left to pay. */
  @Test
  void aSurplusIsDiscountedFromTheNextPeriodEverywhere() throws Exception {
    Long september = approved(contractGuest, contract, null, "2026-09", "850");

    mockMvc
        .perform(get("/bff/guest/account-status").cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$[9].period").value("2026-10"))
        .andExpect(jsonPath("$[9].creditApplied").value(50))
        .andExpect(jsonPath("$[9].amountDue").value(750));
    mockMvc
        .perform(get("/bff/guest/credit").cookie(guest()))
        .andExpect(jsonPath("$.balance").value(0))
        .andExpect(jsonPath("$.toRefund").value(false));
    mockMvc
        .perform(get("/bff/guest/payments/" + id(IdKind.PAYMENT, september)).cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.surplus.amount").value(50))
        .andExpect(jsonPath("$.surplus.outcome").value("CREDIT"));
    mockMvc
        .perform(get(URL + "/" + id(IdKind.PAYMENT, september)).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.surplus.outcome").value("CREDIT"));
    // A5.1: September is paid and left out; October is due with the credit discounted
    mockMvc
        .perform(
            get("/bff/admin/guests/" + id(IdKind.GUEST, contractGuest) + "/payable-periods")
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(9))
        .andExpect(jsonPath("$[?(@.period == '2026-09')]").isEmpty())
        .andExpect(jsonPath("$[8].period").value("2026-10"))
        .andExpect(jsonPath("$[8].status").value("OVERDUE"))
        .andExpect(jsonPath("$[8].creditApplied").value(50))
        .andExpect(jsonPath("$[8].amountDue").value(750))
        .andExpect(jsonPath("$[8].lastPeriod").value(false));
    // RN-16 with RN-38: 750 cover October once the credit is discounted, so it is approved
    Long october =
        insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-13T15:00:00Z");
    jdbc.update("UPDATE payments SET amount = 750 WHERE id = ?", october);
    jdbc.update("UPDATE payment_proofs SET declared_amount = 750 WHERE payment_id = ?", october);
    mockMvc
        .perform(get(URL + "/" + id(IdKind.PAYMENT, october)).cookie(admin()))
        .andExpect(jsonPath("$.creditApplied").value(50))
        .andExpect(jsonPath("$.amountDue").value(750));
    resolve(october, approval(october))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    mockMvc
        .perform(get("/bff/guest/payments/" + id(IdKind.PAYMENT, october)).cookie(guest()))
        .andExpect(jsonPath("$.creditApplied").value(50))
        .andExpect(jsonPath("$.surplus").isEmpty());
    // A stay of a temporary guest without a payment is payable; one paid is not
    mockMvc
        .perform(
            get("/bff/admin/guests/" + id(IdKind.GUEST, temporaryGuest) + "/payable-periods")
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** T1: a stay keeps no credit; what it was paid over is refunded and the guest sees it. */
  @Test
  void theTemporaryGuestSeesTheSurplusOfItsStayAndWhenItCameBack() throws Exception {
    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(temporary()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.surplus.amount").value(50))
        .andExpect(jsonPath("$.surplus.refundStatus").value("PENDING"));
    refund(temporaryGuest, "50", "2026-10-13", "Transferencia").andExpect(status().isCreated());

    mockMvc
        .perform(get("/bff/temporary-guest/payment-status").cookie(temporary()))
        .andExpect(jsonPath("$.surplus.refundStatus").value("REFUNDED"))
        .andExpect(jsonPath("$.surplus.refundedOn").value("2026-10-13"))
        .andExpect(jsonPath("$.surplus.refundMethod").value("Transferencia"));
    mockMvc
        .perform(get(URL + "/" + id(IdKind.PAYMENT, ofStay)).cookie(admin()))
        .andExpect(jsonPath("$.surplus.outcome").value("REFUNDED"));
  }
}
