package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * TAR-42: the guest uploads its own voucher and reads the state of its payments (RF-04, RF-06,
 * CU-16, CU-17, CU-21, CU-22), against the shared contract. The day is 2026-10-14 in Lima; the
 * guest with a contract pays from 2026-01 on the 5th with 3 days of grace, and the temporary guest
 * is in a stay from 2026-10-01 to 2026-10-20.
 */
class GuestPaymentsContractTest extends PaymentsContractSupport {

  private static final String GUEST_URL = "/bff/guest/payments";
  private static final String TEMPORARY_URL = "/bff/temporary-guest/payments";
  private static final String ACCOUNT_STATUS = "/bff/guest/account-status";
  private static final String STAY_STATUS = "/bff/temporary-guest/payment-status";

  private final Long temporaryAccountId = TestIds.next();

  @BeforeEach
  void temporaryAccount() {
    insertAccount(temporaryAccountId, "GUEST", "00000003", temporaryGuest);
  }

  private Cookie temporary() {
    return new Cookie(
        "sessionToken", jwtTokenProvider.renew(temporaryAccountId.toString(), sessionId));
  }

  private static MockMultipartHttpServletRequestBuilder form(String url, byte[] content) {
    return multipart(url).file(new MockMultipartFile("file", "v.pdf", "application/pdf", content));
  }

  private ResultActions send(MockMultipartHttpServletRequestBuilder request, Cookie who)
      throws Exception {
    return mockMvc.perform(request.header("Origin", ORIGIN).cookie(who));
  }

  private ResultActions period(String period, String amount) throws Exception {
    return send(
        form(GUEST_URL, PDF)
            .param("period", period)
            .param("amount", amount)
            .param("paymentMethod", "Yape"),
        guest());
  }

  private ResultActions stayVoucher(String amount) throws Exception {
    return send(form(TEMPORARY_URL, PDF).param("amount", amount), temporary());
  }

  private int payments() {
    Integer count = jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class);
    return count == null ? 0 : count;
  }

  // ------------------------------------------------------------------ the upload

  @Test
  void aGuestWithAContractUploadsAPendingVoucherOfTheMonthAndNeverSeesItsUrl() throws Exception {
    MvcResult result =
        period("2026-10", "800.5")
            .andExpect(status().isCreated())
            .andExpect(answersTheContract())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.period").value("2026-10"))
            .andExpect(jsonPath("$.guestId").value(id(IdKind.GUEST, contractGuest)))
            .andExpect(jsonPath("$.contractId").value(id(IdKind.CONTRACT, contract)))
            .andReturn();

    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body)
        .containsEntry("stayId", null)
        .containsEntry("rejectionReason", null)
        .containsEntry("reviewedAt", null)
        .doesNotContainKey("voucherUrl");
    assertThat(result.getResponse().getContentAsString()).contains("\"amount\":800.50");
    verify(files).store(PDF, VoucherType.PDF);
    verify(files, never()).signedUrl(any(), any());
    assertThat(
            jdbc.queryForMap(
                "SELECT status, recorded_by, reviewed_by_id, guest_id, voucher_key FROM payments"))
        .containsEntry("status", "PENDING")
        .containsEntry("recorded_by", "GUEST")
        .containsEntry("reviewed_by_id", null)
        .containsEntry("guest_id", contractGuest)
        .containsEntry("voucher_key", KEY);
  }

  @Test
  void aTemporaryGuestUploadsTheVoucherOfItsStayWithoutChoosingIt() throws Exception {
    MvcResult result =
        stayVoucher("150")
            .andExpect(status().isCreated())
            .andExpect(answersTheContract())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.stayId").value(id(IdKind.STAY, stay)))
            .andExpect(jsonPath("$.guestId").value(id(IdKind.GUEST, temporaryGuest)))
            .andReturn();

    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body)
        .containsEntry("contractId", null)
        .containsEntry("period", null)
        .containsEntry("paymentMethod", null);
  }

  @Test
  void eachTypeOfGuestIsRefusedTheRoutesOfTheOtherOne() throws Exception {
    send(form(GUEST_URL, PDF).param("period", "2026-10").param("amount", "150"), temporary())
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    send(form(TEMPORARY_URL, PDF).param("amount", "800"), guest())
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(ACCOUNT_STATUS).cookie(temporary()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(STAY_STATUS).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    verify(files, never()).store(any(), any());
    assertThat(payments()).isZero();
  }

  @Test
  void anAdministratorAndAnAnonymousRequestDoNotReachTheGuestRoutes() throws Exception {
    send(form(GUEST_URL, PDF).param("period", "2026-10").param("amount", "800"), admin())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(form(TEMPORARY_URL, PDF).param("amount", "150").header("Origin", ORIGIN))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(ACCOUNT_STATUS).cookie(admin())).andExpect(status().isForbidden());
    mockMvc.perform(get(ACCOUNT_STATUS)).andExpect(status().isUnauthorized());
  }

  @Test
  void aGuestThatHasNotFinishedTheOnboardingIsSentBackToIt() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ONBOARDING'");

    period("2026-10", "800")
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    stayVoucher("150")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    mockMvc
        .perform(get(ACCOUNT_STATUS).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    mockMvc
        .perform(get(STAY_STATUS).cookie(temporary()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void anInactiveGuestTakesNoNewPayment() throws Exception {
    jdbc.update("UPDATE guests SET status = 'INACTIVE'");

    period("2026-10", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(jsonPath("$.guestId").value(id(IdKind.GUEST, contractGuest)));
    stayVoucher("150")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodWithAVoucherPendingOrAlreadyApprovedTakesNoOtherOneButARejectedOneDoes()
      throws Exception {
    insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-09-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-08", "REJECTED", "2026-08-02T10:00:00Z");

    period("2026-10", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"));
    period("2026-09", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
    verify(files, never()).store(any(), any());
    period("2026-08", "800").andExpect(status().isCreated());
  }

  @Test
  void aStayAllowsOneVoucherOnly() throws Exception {
    stayVoucher("150").andExpect(status().isCreated());

    stayVoucher("150")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"));
    jdbc.update(
        "UPDATE payments SET status = 'APPROVED', reviewed_at = now(), reviewed_by_id = ?",
        adminId);
    stayVoucher("150")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
    assertThat(payments()).isEqualTo(1);
  }

  @Test
  void withoutAContractInForceOrAStayInCourseTheGuestCannotPay() throws Exception {
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = '2026-10-10T15:00:00Z'");
    jdbc.update("UPDATE stays SET start_date = '2026-09-01', end_date = '2026-09-10'");

    period("2026-09", "800")
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_NO_ACTIVE_CONTRACT"));
    stayVoucher("150")
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_NO_ACTIVE_STAY"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodOutsideTheContractOrAfterTheCurrentMonthIsAValidationError() throws Exception {
    for (String period : new String[] {"2025-12", "2026-11", "2026-13", "202610", "2026-1"}) {
      period(period, "800")
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("period"));
    }
    send(form(GUEST_URL, PDF).param("amount", "800"), guest())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void anAmountOutOfRangeOrWithAnExponentIsAValidationError() throws Exception {
    for (String amount : new String[] {"0", "-1", "0.001", "100000000", "abc", "8E+2"}) {
      period("2026-10", amount)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("amount"));
      stayVoucher(amount)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("amount"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void theGuestIsTheOneOfTheSessionAndAnyOtherFieldIsRefused() throws Exception {
    // a guest, a contract or a stay named in the form is never read: the form is closed
    send(
            form(GUEST_URL, PDF)
                .param("period", "2026-10")
                .param("amount", "800")
                .param("guestId", id(IdKind.GUEST, temporaryGuest)),
            guest())
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("guestId"));
    send(
            form(GUEST_URL, PDF)
                .param("period", "2026-10")
                .param("amount", "800")
                .param("contractId", id(IdKind.CONTRACT, contract)),
            guest())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("contractId"));
    send(form(TEMPORARY_URL, PDF).param("amount", "150").param("period", "2026-10"), temporary())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    send(
            form(TEMPORARY_URL, PDF).param("amount", "150").param("stayId", id(IdKind.STAY, stay)),
            temporary())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("stayId"));
    send(
            form(TEMPORARY_URL, PDF)
                .file(new MockMultipartFile("copy", PDF))
                .param("amount", "150"),
            temporary())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("copy"));
    assertThat(payments()).isZero();
  }

  @Test
  void aFileThatIsNotAVoucherByItsContentIsRefused() throws Exception {
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    byte[] active = TestPdfs.withJavaScript();

    for (byte[] content : new byte[][] {exe, active, new byte[0]}) {
      send(form(GUEST_URL, content).param("period", "2026-10").param("amount", "800"), guest())
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"))
          .andExpect(jsonPath("$.field").value("file"));
      send(form(TEMPORARY_URL, content).param("amount", "150"), temporary())
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void anUploadThatDoesNotSayWhereItComesFromIsRefused() throws Exception {
    mockMvc
        .perform(
            form(GUEST_URL, PDF).param("period", "2026-10").param("amount", "800").cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(form(TEMPORARY_URL, PDF).param("amount", "150").cookie(temporary()))
        .andExpect(status().isForbidden());
    verify(files, never()).store(any(), any());
  }

  // ------------------------------------------------------------------ the state of the payments

  @Test
  void theAccountStatusListsEveryMonthUpToTheCurrentOneWithItsState() throws Exception {
    Long approved =
        insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-09-02T10:00:00Z");
    Long rejected =
        insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-02T10:00:00Z");

    MvcResult result =
        mockMvc
            .perform(get(ACCOUNT_STATUS).cookie(guest()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].period").value("2026-01"))
            .andExpect(jsonPath("$[0].status").value("OVERDUE"))
            .andExpect(jsonPath("$[0].dueDate").value("2026-01-05"))
            .andExpect(jsonPath("$[0].amount").value(800))
            .andExpect(jsonPath("$[0].contractId").value(id(IdKind.CONTRACT, contract)))
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
  }

  @Test
  void aVoucherUnderReviewHidesTheReasonOfAnEarlierRejection() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-03T10:00:00Z");

    mockMvc
        .perform(get(ACCOUNT_STATUS).cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[9].status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$[9].rejectionReason").isEmpty());
  }

  @Test
  void theStayStatusShowsTheStayInCourseAndItsPayment() throws Exception {
    MvcResult unpaid =
        mockMvc
            .perform(get(STAY_STATUS).cookie(temporary()))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.stayId").value(id(IdKind.STAY, stay)))
            .andExpect(jsonPath("$.startDate").value("2026-10-01"))
            .andExpect(jsonPath("$.endDate").value("2026-10-20"))
            .andExpect(jsonPath("$.status").value("UNPAID"))
            .andExpect(jsonPath("$.agreedAmount").value(150))
            .andReturn();
    Map<String, Object> body = JsonPath.read(unpaid.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("paymentId", null).containsEntry("rejectionReason", null);

    Long pending =
        insertPayment(temporaryGuest, null, stay, null, "PENDING", "2026-10-02T10:00:00Z");
    mockMvc
        .perform(get(STAY_STATUS).cookie(temporary()))
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.paymentId").value(id(IdKind.PAYMENT, pending)));
  }

  @Test
  void aTemporaryGuestWithoutAnyStayHasNoPaymentStatus() throws Exception {
    jdbc.update("DELETE FROM stays");

    mockMvc
        .perform(get(STAY_STATUS).cookie(temporary()))
        .andExpect(status().isNotFound())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }
}
