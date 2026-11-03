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
import org.springframework.http.HttpMethod;
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

  /** TAR-247, H3: the text of a period whose payment is already under review. */
  private static final String PERIOD_VOUCHER_PENDING =
      "Ya enviaste un pago para este período y la administración lo está revisando.";

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
    return multipart(url).file(new MockMultipartFile("files", "v.pdf", "application/pdf", content));
  }

  private ResultActions send(MockMultipartHttpServletRequestBuilder request, Cookie who)
      throws Exception {
    return mockMvc.perform(request.header("Origin", ORIGIN).cookie(who));
  }

  private ResultActions period(String period, String amount) throws Exception {
    return send(form(GUEST_URL, PDF).param("period", period).file(proofs(amount, "Yape")), guest());
  }

  private ResultActions stayVoucher(String amount) throws Exception {
    return send(form(TEMPORARY_URL, PDF).file(proofs(amount, null)), temporary());
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
            jdbc.queryForMap("SELECT status, recorded_by, reviewed_by_id, guest_id FROM payments"))
        .containsEntry("status", "PENDING")
        .containsEntry("recorded_by", "GUEST")
        .containsEntry("reviewed_by_id", null)
        .containsEntry("guest_id", contractGuest);
    assertThat(jdbc.queryForMap("SELECT file_key, method, uploaded_by FROM payment_proofs"))
        .containsEntry("file_key", KEY)
        .containsEntry("method", "Yape")
        .containsEntry("uploaded_by", "GUEST");
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
    send(form(GUEST_URL, PDF).param("period", "2026-10").file(proofs("150", null)), temporary())
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    send(form(TEMPORARY_URL, PDF).file(proofs("800", null)), guest())
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
    send(form(GUEST_URL, PDF).param("period", "2026-10").file(proofs("800", null)), admin())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(form(TEMPORARY_URL, PDF).file(proofs("150", null)).header("Origin", ORIGIN))
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
        .andExpect(
            jsonPath("$.message")
                .value(
                    "No puedes enviar pagos mientras tu cuenta no esté activa. Consulta con la"
                        + " administración."))
        .andExpect(jsonPath("$.guestId").value(id(IdKind.GUEST, contractGuest)));
    stayVoucher("150")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "No puedes enviar pagos mientras tu cuenta no esté activa. Consulta con la"
                        + " administración."));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodWithAPaymentPendingApprovedOrRejectedTakesNoOtherOne() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-09-02T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-08", "REJECTED", "2026-08-02T10:00:00Z");

    period("2026-10", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"))
        .andExpect(jsonPath("$.message").value(PERIOD_VOUCHER_PENDING));
    period("2026-09", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"))
        .andExpect(jsonPath("$.message").value("Ya existe un pago aprobado para este período"));
    // TAR-239: a rejected payment is corrected with correctOwnPayment, not replaced
    period("2026-08", "800")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"))
        .andExpect(jsonPath("$.message").value(PERIOD_VOUCHER_PENDING));
    verify(files, never()).store(any(), any());
  }

  /** H3 «Enviar de nuevo» (RN-05): the guest corrects its rejected payment and sends it again. */
  @Test
  void theGuestCorrectsItsRejectedPaymentAndSendsItAgain() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-08", "REJECTED", "2026-08-02T10:00:00Z");
    String url = GUEST_URL + "/" + id(IdKind.PAYMENT, id);
    Long voucher =
        jdbc.queryForObject("SELECT id FROM payment_proofs WHERE payment_id = ?", Long.class, id);

    mockMvc
        .perform(get(url).cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("No coincide"))
        .andExpect(jsonPath("$.proofs[0].fileUrl").doesNotExist())
        .andExpect(jsonPath("$.reviewedBy").doesNotExist())
        .andExpect(jsonPath("$.amountDue").value(800));
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, url)
                .file(new MockMultipartFile("files", "nuevo.pdf", "application/pdf", PDF))
                .file(
                    proofsPart(
                        "[{\"id\":\""
                            + id(IdKind.PAYMENT_PROOF, voucher)
                            + "\",\"kind\":\"VOUCHER\",\"amount\":800,\"fileIndex\":0}]"))
                .header("Origin", ORIGIN)
                .cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.submittedAt").value("2026-10-14T14:00:00Z"))
        .andExpect(jsonPath("$.rejectionReason").value((Object) null));
    // the replaced voucher leaves the bucket once the correction commits
    verify(files).delete(KEY + "-" + id);
    // a pending payment is read-only for the guest (RN-15)
    mockMvc
        .perform(
            multipart(HttpMethod.PUT, url)
                .file(proofsPart("[{\"kind\":\"CASH\",\"amount\":800}]"))
                .header("Origin", ORIGIN)
                .cookie(guest()))
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CORRECTABLE"))
        .andExpect(
            jsonPath("$.message")
                .value("Este pago ya no se puede corregir: está en revisión o ya se aprobó"));
  }

  /** RN-09: a payment of another guest does not exist for this one. */
  @Test
  void aPaymentOfAnotherGuestIsNotFound() throws Exception {
    Long ofStay =
        insertPayment(temporaryGuest, null, stay, null, "REJECTED", "2026-10-02T10:00:00Z");

    mockMvc
        .perform(get(GUEST_URL + "/" + id(IdKind.PAYMENT, ofStay)).cookie(guest()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get(TEMPORARY_URL + "/" + id(IdKind.PAYMENT, ofStay)).cookie(temporary()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.stayStartDate").value("2026-10-01"));
  }

  @Test
  void aStayAllowsOneVoucherOnly() throws Exception {
    stayVoucher("150").andExpect(status().isCreated());

    stayVoucher("150")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Ya enviaste un pago para esta estadía y la administración lo está"
                        + " revisando."));
    jdbc.update(
        "UPDATE payments SET status = 'APPROVED', verified_amount = amount, reviewed_at = now(),"
            + " reviewed_by_id = ?",
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
    send(form(GUEST_URL, PDF).file(proofs("800", null)), guest())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void anAmountOutOfRangeOrWithAnExponentIsAValidationError() throws Exception {
    for (String amount : new String[] {"0", "-1", "0.001", "100000000", "8E+2"}) {
      period("2026-10", amount)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("proofs[0].amount"));
      stayVoucher(amount)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("proofs[0].amount"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void theGuestIsTheOneOfTheSessionAndAnyOtherFieldIsRefused() throws Exception {
    // a guest, a contract or a stay named in the form is never read: the form is closed
    send(
            form(GUEST_URL, PDF)
                .param("period", "2026-10")
                .file(proofs("800", null))
                .param("guestId", id(IdKind.GUEST, temporaryGuest)),
            guest())
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("guestId"));
    send(
            form(GUEST_URL, PDF)
                .param("period", "2026-10")
                .file(proofs("800", null))
                .param("contractId", id(IdKind.CONTRACT, contract)),
            guest())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("contractId"));
    send(form(TEMPORARY_URL, PDF).file(proofs("150", null)).param("period", "2026-10"), temporary())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    send(
            form(TEMPORARY_URL, PDF)
                .file(proofs("150", null))
                .param("stayId", id(IdKind.STAY, stay)),
            temporary())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("stayId"));
    send(
            form(TEMPORARY_URL, PDF)
                .file(new MockMultipartFile("copy", PDF))
                .file(proofs("150", null)),
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
      send(form(GUEST_URL, content).param("period", "2026-10").file(proofs("800", null)), guest())
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"))
          .andExpect(jsonPath("$.field").value("proofs[0].file"));
      send(form(TEMPORARY_URL, content).file(proofs("150", null)), temporary())
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void anUploadThatDoesNotSayWhereItComesFromIsRefused() throws Exception {
    mockMvc
        .perform(
            form(GUEST_URL, PDF)
                .param("period", "2026-10")
                .file(proofs("800", null))
                .cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(form(TEMPORARY_URL, PDF).file(proofs("150", null)).cookie(temporary()))
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
    // a rejection of before TAR-239 that another payment replaced is history
    insertPayment(contractGuest, contract, null, "2026-10", "SUPERSEDED", "2026-10-02T10:00:00Z");
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
