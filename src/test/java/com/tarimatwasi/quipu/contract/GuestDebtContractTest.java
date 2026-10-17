package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * TAR-184 (RN-25, RN-35, D-27, D-31): a guest that owes periods takes no new contract, and the 409
 * satisfies the shared contract. The seed contract of the guest charges from 2026-01 on the 5th;
 * the day is 2026-10-14 in Lima, so January to October are due.
 */
class GuestDebtContractTest extends PaymentsContractSupport {

  private static final String CONTRACTS = "/bff/admin/contracts";

  @MockitoBean ContractFilePort contractFiles;

  private Long room2;

  @BeforeEach
  void anotherFreeRoom() {
    when(contractFiles.store(any())).thenReturn("contracts/fake.pdf");
    when(contractFiles.signedUrl(anyString())).thenReturn(SIGNED);
    room2 = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '202', 'ROOM', 'ACTIVE')",
        room2);
  }

  @Test
  void onlyAnApprovedPaymentClearsAPeriodAndTheAnswerListsWhatIsOwed() throws Exception {
    for (int month = 1; month <= 8; month++) {
      insertPayment(contractGuest, contract, null, "2026-0" + month, "APPROVED", "2026-10-01");
    }
    insertPayment(contractGuest, contract, null, "2026-09", "PENDING", "2026-10-01");
    insertPayment(contractGuest, contract, null, "2026-10", "REJECTED", "2026-10-01");

    load(room2)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_HAS_PENDING_DEBT"))
        .andExpect(jsonPath("$.periods.length()").value(2))
        .andExpect(jsonPath("$.periods[0]").value("2026-09"))
        .andExpect(jsonPath("$.periods[1]").value("2026-10"));
    verify(contractFiles, never()).store(any());
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void finishingAContractWithDebtIsAllowedButTheDebtStaysWithTheGuest() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post(
                    CONTRACTS + "/" + masker.mask(IdKind.CONTRACT, contract) + "/terminate")
                .cookie(admin()))
        .andExpect(status().isNoContent());

    load(room2)
        .andExpect(status().isConflict())
        .andExpect(answersTheContract())
        .andExpect(jsonPath("$.periods.length()").value(10))
        .andExpect(jsonPath("$.periods[0]").value("2026-01"));
  }

  @Test
  void aGuestThatPaidEverythingTakesTheNewContract() throws Exception {
    for (int month = 1; month <= 10; month++) {
      insertPayment(
          contractGuest,
          contract,
          null,
          String.format("2026-%02d", month),
          "APPROVED",
          "2026-10-01");
    }

    load(room2).andExpect(status().isCreated()).andExpect(answersTheContract());
    assertThat(count()).isEqualTo(2);
  }

  @Test
  void theDebtOfAnotherGuestDoesNotBlockThisOne() throws Exception {
    Long other = insertGuest("44444444", "CONTRACT", "ACTIVE", null);

    load(room2, other).andExpect(status().isCreated());
  }

  private ResultActions load(Long environment) throws Exception {
    return load(environment, contractGuest);
  }

  private ResultActions load(Long environment, Long guestId) throws Exception {
    return mockMvc.perform(
        multipart(CONTRACTS)
            .file(new MockMultipartFile("file", "c.pdf", "application/pdf", PDF))
            .param("environmentId", id(IdKind.ENVIRONMENT, environment))
            .param("guestId", id(IdKind.GUEST, guestId))
            .param("monthlyAmount", "850.50")
            .param("paymentDay", "5")
            .param("graceDays", "3")
            .param("startDate", "2026-11-01")
            .header("Origin", ORIGIN)
            .cookie(admin()));
  }

  private int count() {
    return jdbc.queryForObject("SELECT count(*) FROM contracts", Integer.class);
  }
}
