package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TAR-45 (RF-10, CU-19, RN-09): the payment history of a guest, newest first and read by last
 * index, as the guest with a contract sees its own and as the administrator sees the one of any
 * guest, against the shared contract. Neither one carries the URL of the voucher.
 */
class PaymentHistoryContractTest extends PaymentsContractSupport {

  private static final String OWN = "/bff/guest/payments";

  private final Long temporaryAccountId = TestIds.next();

  @BeforeEach
  void temporaryAccount() {
    insertAccount(temporaryAccountId, "GUEST", "00000003", temporaryGuest);
  }

  private Cookie temporary() {
    return new Cookie(
        "sessionToken", jwtTokenProvider.renew(temporaryAccountId.toString(), sessionId));
  }

  private String adminUrl(Long guestId) {
    return adminUrl(ids().mask(IdKind.GUEST, guestId));
  }

  private static String adminUrl(UUID guestId) {
    return "/bff/admin/guests/" + guestId + "/payments";
  }

  private ResultActions read(String url, Cookie who, String... params) throws Exception {
    var request = get(url).cookie(who);
    for (int i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request);
  }

  /** Three payments of the guest with a contract, oldest first, and one of the temporary guest. */
  private List<Long> history() {
    List<Long> created = new ArrayList<>();
    created.add(
        insertPayment(
            contractGuest, contract, null, "2026-08", "APPROVED", "2026-08-03T10:00:00Z"));
    created.add(
        insertPayment(
            contractGuest, contract, null, "2026-09", "REJECTED", "2026-09-03T10:00:00Z"));
    created.add(
        insertPayment(contractGuest, contract, null, "2026-10", "PENDING", "2026-10-03T10:00:00Z"));
    insertPayment(temporaryGuest, null, stay, null, "APPROVED", "2026-10-05T10:00:00Z");
    return created;
  }

  private static List<String> ids(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].id");
  }

  private static @Nullable String cursor(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.nextCursor");
  }

  /** Walks every page of the list, two by two, and returns the ids in the order they came. */
  private List<String> walk(String url, Cookie who) throws Exception {
    List<String> seen = new ArrayList<>();
    String after = null;
    do {
      MvcResult page =
          (after == null
                  ? read(url, who, "limit", "2")
                  : read(url, who, "limit", "2", "after", after))
              .andExpect(status().isOk())
              .andExpect(satisfiesTheContract())
              .andReturn();
      seen.addAll(ids(page));
      after = cursor(page);
    } while (after != null);
    return seen;
  }

  // ------------------------------------------------------------------ the guest with a contract

  @Test
  void theGuestReadsItsOwnHistoryNewestFirstWithTheReasonOfARejectionAndNoVoucherUrl()
      throws Exception {
    List<Long> created = history();

    MvcResult result =
        read(OWN, guest())
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.content.length()").value(3))
            .andExpect(jsonPath("$.content[0].status").value("PENDING"))
            .andExpect(jsonPath("$.content[1].status").value("REJECTED"))
            .andExpect(jsonPath("$.content[1].rejectionReason").value("No coincide"))
            .andExpect(jsonPath("$.content[2].period").value("2026-08"))
            .andReturn();

    assertThat(ids(result))
        .containsExactly(
            id(IdKind.PAYMENT, created.get(2)),
            id(IdKind.PAYMENT, created.get(1)),
            id(IdKind.PAYMENT, created.get(0)));
    Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
    assertThat(body).containsEntry("nextCursor", null);
    Map<String, Object> pending =
        JsonPath.read(result.getResponse().getContentAsString(), "$.content[0]");
    assertThat(pending)
        .containsEntry("stayId", null)
        .containsEntry("rejectionReason", null)
        .containsEntry("reviewedAt", null)
        .containsEntry("paymentMethod", null)
        .containsEntry("guestId", id(IdKind.GUEST, contractGuest))
        .containsEntry("contractId", id(IdKind.CONTRACT, contract))
        .doesNotContainKey("voucherUrl");
    verify(files, never()).signedUrl(any(), any());
  }

  @Test
  void theGuestWalksItsHistoryPageByPageWithoutRepeatingOrSkipping() throws Exception {
    List<Long> created = history();

    assertThat(walk(OWN, guest()))
        .containsExactly(
            id(IdKind.PAYMENT, created.get(2)),
            id(IdKind.PAYMENT, created.get(1)),
            id(IdKind.PAYMENT, created.get(0)));
  }

  @Test
  void theGuestIsTheOneOfTheSessionWhateverTheRequestNames() throws Exception {
    history();

    MvcResult result =
        read(OWN, guest(), "guestId", id(IdKind.GUEST, temporaryGuest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3))
            .andReturn();

    List<String> owners =
        JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].guestId");
    assertThat(owners).containsOnly(id(IdKind.GUEST, contractGuest));
  }

  @Test
  void aTemporaryGuestAnAdministratorAndAnAnonymousRequestDoNotReachTheOwnHistory()
      throws Exception {
    history();

    read(OWN, temporary())
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    read(OWN, admin())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(OWN)).andExpect(status().isUnauthorized());
  }

  @Test
  void aGuestThatHasNotFinishedTheOnboardingIsSentBackToIt() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ONBOARDING'");

    read(OWN, guest())
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
  }

  @Test
  void anInactiveGuestStillReadsItsHistory() throws Exception {
    history();
    jdbc.update("UPDATE guests SET status = 'INACTIVE'");

    read(OWN, guest())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(3));
  }

  @Test
  void aLimitOutOfRangeOrACursorThatIsNotOursIsAValidationErrorOfTheGuest() throws Exception {
    for (String limit : new String[] {"0", "101", "x"}) {
      read(OWN, guest(), "limit", limit)
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("limit"));
    }
    for (String after :
        new String[] {"nope", CursorToken.encode("+12345-01-01T00:00:00", "1"), "a".repeat(513)}) {
      read(OWN, guest(), "after", after)
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.field").value("after"));
    }
  }

  // ------------------------------------------------------------------ the administrator

  @Test
  void theAdministratorReadsTheHistoryOfAGuestOfEitherTypeNewestFirstWithoutTheVoucherUrl()
      throws Exception {
    List<Long> created = history();

    MvcResult ofContract =
        read(adminUrl(contractGuest), admin())
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.content[1].rejectionReason").value("No coincide"))
            .andReturn();
    assertThat(ids(ofContract))
        .containsExactly(
            id(IdKind.PAYMENT, created.get(2)),
            id(IdKind.PAYMENT, created.get(1)),
            id(IdKind.PAYMENT, created.get(0)));
    assertThat(ofContract.getResponse().getContentAsString()).doesNotContain("voucherUrl");

    MvcResult ofStay =
        read(adminUrl(temporaryGuest), admin())
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].stayId").value(id(IdKind.STAY, stay)))
            .andReturn();
    Map<String, Object> payment =
        JsonPath.read(ofStay.getResponse().getContentAsString(), "$.content[0]");
    assertThat(payment)
        .containsEntry("contractId", null)
        .containsEntry("period", null)
        .doesNotContainKey("voucherUrl");
  }

  @Test
  void theAdministratorWalksTheHistoryPageByPage() throws Exception {
    List<Long> created = history();

    assertThat(walk(adminUrl(contractGuest), admin()))
        .containsExactly(
            id(IdKind.PAYMENT, created.get(2)),
            id(IdKind.PAYMENT, created.get(1)),
            id(IdKind.PAYMENT, created.get(0)));
  }

  @Test
  void aGuestWithoutPaymentsHasAnEmptyHistoryAndOneThatDoesNotExistIsNotFound() throws Exception {
    Long withoutPayments = insertGuest("11112222", "CONTRACT", "ACTIVE", "Luis");

    read(adminUrl(withoutPayments), admin())
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content.length()").value(0));
    // A masked id of a guest that is not there, and one this service never issued.
    for (String url : new String[] {adminUrl(TestIds.next()), adminUrl(UUID.randomUUID())}) {
      read(url, admin())
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.code").value("GUEST_NOT_FOUND"));
    }
  }

  @Test
  void theParametersAreCheckedBeforeTheGuestSoAForgedCursorIsA400AndNotA500() throws Exception {
    for (String url : new String[] {adminUrl(contractGuest), adminUrl(UUID.randomUUID())}) {
      read(url, admin(), "limit", "101")
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.field").value("limit"));
      for (String after :
          new String[] {
            "nope",
            CursorToken.encode("not-a-date", "1"),
            CursorToken.encode("+12345-01-01T00:00:00", "1"),
            CursorToken.encode("2026-01-01T00:00:00", "x")
          }) {
        read(url, admin(), "after", after)
            .andExpect(status().isBadRequest())
            .andExpect(satisfiesTheContract())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.field").value("after"));
      }
    }
    read("/bff/admin/guests/not-a-uuid/payments", admin())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aGuestAndAnAnonymousRequestDoNotReachTheHistoryOfTheAdministrator() throws Exception {
    read(adminUrl(contractGuest), guest())
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc.perform(get(adminUrl(contractGuest))).andExpect(status().isUnauthorized());
  }
}
