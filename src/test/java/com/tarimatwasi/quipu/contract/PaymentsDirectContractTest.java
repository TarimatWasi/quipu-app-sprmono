package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.shared.file.PdfInspections;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * TAR-43: the payment the administrator registers by hand (CU-14) and who may reach the routes,
 * against the shared contract.
 */
class PaymentsDirectContractTest extends PaymentsContractSupport {

  @Test
  void aPaymentForAPeriodIsRegisteredApprovedAtOnce() throws Exception {
    MvcResult result =
        post(contractGuest, "period", "2026-10", "850.50", PDF, "../../v.pdf")
            .andExpect(status().isCreated())
            .andExpect(answersTheContract())
            .andExpect(
                saved ->
                    assertThat(saved.getResponse().getHeader("Location")).startsWith(URL + "/"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.period").value("2026-10"))
            .andExpect(jsonPath("$.amount").value(850.50))
            .andExpect(jsonPath("$.paymentMethod").value("Efectivo"))
            .andExpect(
                jsonPath("$.contractId").value(ids().mask(IdKind.CONTRACT, contract).toString()))
            .andReturn();
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("stayId", null).containsEntry("rejectionReason", null);
    verify(files).store(PDF, VoucherType.PDF);
    assertThat(jdbc.queryForObject("SELECT reviewed_by_id FROM payments", Long.class))
        .isEqualTo(adminId);
    assertThat(jdbc.queryForObject("SELECT recorded_by FROM payments", String.class))
        .isEqualTo("ADMIN");
    assertThat(jdbc.queryForObject("SELECT original_name FROM payments", String.class))
        .isEqualTo("v.pdf");
    assertThat(jdbc.queryForObject("SELECT voucher_key FROM payments", String.class))
        .isEqualTo(KEY);
  }

  @Test
  void aPaymentForAStayIsRegisteredApprovedAtOnce() throws Exception {
    post(temporaryGuest, "stayId", ids().mask(IdKind.STAY, stay).toString(), "150.50", PDF, "v.pdf")
        .andExpect(status().isCreated())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.stayId").value(ids().mask(IdKind.STAY, stay).toString()));
  }

  @Test
  void aFileThatIsNotAVoucherByItsContentIsRefused() throws Exception {
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    byte[] big = new byte[5 * 1024 * 1024 + 1];
    System.arraycopy(PDF, 0, big, 0, PDF.length);
    byte[] active = TestPdfs.withJavaScript();

    for (byte[] content : new byte[][] {exe, big, active, new byte[0]}) {
      post(contractGuest, "period", "2026-10", "800", content, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(answersTheContract())
          .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE"))
          .andExpect(jsonPath("$.field").value("file"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void bothOrNeitherOfPeriodAndStayAndABadAmountAreValidationErrors() throws Exception {
    postForm(builder(PDF).param("guestId", id(IdKind.GUEST, contractGuest)).param("amount", "800"))
        .andExpect(status().isBadRequest())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("period", "2026-10")
                .param("stayId", id(IdKind.STAY, stay)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    for (String amount : new String[] {"0", "-1", "0.001", "100000000", "abc"}) {
      post(contractGuest, "period", "2026-10", amount, PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("amount"));
    }
    for (String period : new String[] {"2026-13", "2026-1", "202610", "oct-2026", "2026-00"}) {
      post(contractGuest, "period", period, "800", PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("period"));
    }
    verify(files, never()).store(any(), any());
  }

  @Test
  void aMisspelledFieldIsRefusedInsteadOfBeingIgnored() throws Exception {
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("periodo", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("periodo"));
    postForm(
            builder(PDF)
                .file(new MockMultipartFile("copy", PDF))
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("period", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("copy"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void theGuestAndTheStayMustExistAndTheGuestMustBeActive() throws Exception {
    post(999_999L, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    post(
            temporaryGuest,
            "stayId",
            ids().mask(IdKind.STAY, 999_999L).toString(),
            "150",
            PDF,
            "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    // a stay of another guest does not exist for this one
    post(contractGuest, "stayId", ids().mask(IdKind.STAY, stay).toString(), "150", PDF, "v.pdf")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    // D-27: an inactive guest pays only the months it owes; November is not owed
    jdbc.update("UPDATE guests SET status = 'INACTIVE' WHERE id = ?", contractGuest);
    post(contractGuest, "period", "2026-11", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_ACTIVE"))
        .andExpect(jsonPath("$.guestId").value(ids().mask(IdKind.GUEST, contractGuest).toString()));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodTheContractDoesNotChargeIsRefused() throws Exception {
    for (String period : new String[] {"2025-12", "2026-11"}) {
      post(contractGuest, "period", period, "800", PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("period"));
    }
    post(temporaryGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aPeriodWithAPaymentAlreadyApprovedOrPendingIsAConflictAndNeverA500() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-10-01T10:00:00Z");
    insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-02T10:00:00Z");

    post(contractGuest, "period", "2026-09", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_EXISTS"));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aStayWithAPaymentAlreadyApprovedIsAConflict() throws Exception {
    insertPayment(temporaryGuest, null, stay, null, "APPROVED", "2026-10-01T10:00:00Z");

    post(temporaryGuest, "stayId", ids().mask(IdKind.STAY, stay).toString(), "150", PDF, "v.pdf")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_EXISTS_FOR_PERIOD"));
  }

  @Test
  void aRejectedVoucherDoesNotBlockARegisteredPayment() throws Exception {
    insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-01T10:00:00Z");

    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf").andExpect(status().isCreated());
  }

  @Test
  void aBucketThatFailsRegistersNothing() throws Exception {
    when(files.store(any(), any())).thenThrow(new PaymentFileStorageException("down"));

    post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
  }

  @Test
  void onlyAnAdministratorReachesThePaymentRoutes() throws Exception {
    Long id =
        insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01T10:00:00Z");
    String one = URL + "/" + ids().mask(IdKind.PAYMENT, id);

    mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(URL).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(one).cookie(guest())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(one)
                .cookie(guest())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            patch(one).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(builder(PDF).header("Origin", ORIGIN).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(builder(PDF).header("Origin", ORIGIN)).andExpect(status().isUnauthorized());
    assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
        .isEqualTo("PENDING");
    verify(files, never()).store(any(), any());
  }

  @Test
  void aDirectPaymentThatDoesNotSayWhereItComesFromIsRefused() throws Exception {
    mockMvc
        .perform(builder(PDF).cookie(admin()))
        .andExpect(status().isForbidden())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(builder(PDF).header("Origin", "https://evil.example").cookie(admin()))
        .andExpect(status().isForbidden());
    verify(files, never()).store(any(), any());
  }

  @Test
  void theFieldsAreCheckedBeforeAForgedIdIsAnswered() throws Exception {
    UUID forged = UUID.randomUUID();

    postForm(
            builder(PDF)
                .param("guestId", forged.toString())
                .param("amount", "0")
                .param("period", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("amount"));
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, temporaryGuest))
                .param("amount", "150")
                .param("stayId", forged.toString())
                .param("period", "2026-10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("period"));
    postForm(
            builder(PDF)
                .param("guestId", forged.toString())
                .param("amount", "150")
                .param("period", "2026-10"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, temporaryGuest))
                .param("amount", "150")
                .param("stayId", forged.toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, temporaryGuest))
                .param("amount", "150")
                .param("stayId", id(IdKind.CONTRACT, contract)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STAY_NOT_FOUND"));
  }

  @Test
  void aPaymentMethodWithHiddenCharactersAndAScientificAmountAreValidationErrors()
      throws Exception {
    postForm(
            builder(PDF)
                .param("guestId", id(IdKind.GUEST, contractGuest))
                .param("amount", "800")
                .param("period", "2026-10")
                .param("paymentMethod", "Yape\u202E"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("paymentMethod"));
    for (String written : new String[] {"8E+2", "8.00E+2", "8.000e+2", "800E0"}) {
      post(contractGuest, "period", "2026-10", written, PDF, "v.pdf")
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.field").value("amount"));
    }
    post(contractGuest, "period", "2026-10", "8E+2", PDF, "v.pdf")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("amount"));
  }

  @Test
  void aRegisteredAmountComesBackWithTwoDecimals() throws Exception {
    MvcResult result =
        post(contractGuest, "period", "2026-10", "800", PDF, "v.pdf")
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains("\"amount\":800.00");
  }

  @Test
  void aVoucherOverFiveMegabytesIsRefusedBeforeItIsInspected() throws Exception {
    // TAR-39: with the inspection busy, a file read and inspected would answer 503
    byte[] big = TestPdfs.ofSize(6 * 1024 * 1024);

    PdfInspections.whileBusy(
        () ->
            post(contractGuest, "period", "2026-10", "800", big, "v.pdf")
                .andExpect(status().isBadRequest())
                .andExpect(answersTheContract())
                .andExpect(jsonPath("$.code").value("VOUCHER_INVALID_FILE")));
    verify(files, never()).store(any(), any());
  }

  @Test
  void aVoucherThatCannotBeInspectedInTimeIsAServiceUnavailable() throws Exception {
    PdfInspections.whileBusy(
        () ->
            post(contractGuest, "period", "2026-10", "800", TestPdfs.plain(), "v.pdf")
                .andExpect(status().isServiceUnavailable())
                .andExpect(answersTheContract())
                .andExpect(jsonPath("$.code").value("FILE_STORAGE_UNAVAILABLE")));
    verify(files, never()).store(any(), any());
  }
}
