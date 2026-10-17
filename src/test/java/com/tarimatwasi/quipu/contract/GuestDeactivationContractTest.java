package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * TAR-38 (RF-13, RN-12, CU-10, CU-28): deactivating and reactivating a guest, against the shared
 * contract. The seed contract guest has a contract in force since 2026-01 and pays nothing, so on
 * 2026-10-14 it owes January to October; the temporary guest has no contract and owes nothing.
 */
class GuestDeactivationContractTest extends PaymentsContractSupport {

  private static final String GUESTS = "/bff/admin/guests/";
  private static final String PASSWORD = "Temporal123!";

  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void aGuestWithAContractInForceIsNotDeactivatedAndTheAnswerLinksTheContract() throws Exception {
    deactivate(contractGuest)
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_HAS_ACTIVE_CONTRACT"))
        .andExpect(jsonPath("$.contractId").value(id(IdKind.CONTRACT, contract)));
    assertThat(guestRow(contractGuest)).containsEntry("status", "ACTIVE");
    assertThat(accountStatus(guestAccountId)).isEqualTo("ACTIVE");
  }

  /** The debt does not block the deactivation, but it blocks the reactivation (RN-12, RN-35). */
  @Test
  void aGuestThatOwesIsDeactivatedButNotReactivatedUntilItPays() throws Exception {
    terminateTheContract();

    deactivate(contractGuest).andExpect(status().isNoContent()).andExpect(satisfiesTheContract());
    assertThat(guestRow(contractGuest))
        .containsEntry("status", "INACTIVE")
        .containsEntry("status_before_inactive", "ACTIVE");
    assertThat(accountStatus(guestAccountId)).isEqualTo("INACTIVE");

    reactivate(contractGuest)
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_HAS_PENDING_DEBT"))
        .andExpect(jsonPath("$.periods.length()").value(10))
        .andExpect(jsonPath("$.periods[0]").value("2026-01"));
    assertThat(guestRow(contractGuest)).containsEntry("status", "INACTIVE");
    assertThat(accountStatus(guestAccountId)).isEqualTo("INACTIVE");
  }

  @Test
  void afterTheDeactivationTheLoginIsRefusedAndAPreviousSessionStopsWorking() throws Exception {
    terminateTheContract();
    jdbc.update(
        "UPDATE users SET password_hash = ? WHERE id = ?",
        passwordEncoder.encode(PASSWORD),
        guestAccountId);
    var session = guest();
    mockMvc.perform(get("/bff/auth/me").cookie(session)).andExpect(status().isOk());

    deactivate(contractGuest).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/bff/auth/me").cookie(session))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContract());
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/bff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"00000002\",\"password\":\"%s\"}"
                        .formatted(PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));
  }

  @Test
  void aGuestWithoutDebtComesBackWithItsStateAndItsAccessBothTimes() throws Exception {
    Long accountId = TestIds.next();
    insertAccount(accountId, "GUEST", "87654321", temporaryGuest);

    deactivate(temporaryGuest).andExpect(status().isNoContent());
    deactivate(temporaryGuest).andExpect(status().isNoContent()).andExpect(satisfiesTheContract());
    assertThat(guestRow(temporaryGuest)).containsEntry("status_before_inactive", "ACTIVE");

    for (int i = 0; i < 2; i++) {
      reactivate(temporaryGuest)
          .andExpect(status().isOk())
          .andExpect(satisfiesTheContract())
          .andExpect(jsonPath("$.id").value(id(IdKind.GUEST, temporaryGuest)))
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$.hasLoginAccess").value(true));
    }
    assertThat(guestRow(temporaryGuest))
        .containsEntry("status", "ACTIVE")
        .containsEntry("status_before_inactive", null);
    assertThat(accountStatus(accountId)).isEqualTo("ACTIVE");
  }

  /** CU-28: an access that expired meanwhile leaves the guest pending activation, no account. */
  @Test
  void aGuestPendingActivationWithoutAccountComesBackPendingActivation() throws Exception {
    Long pending = insertGuest("11223344", "CONTRACT", "PENDING_ACTIVATION", null);

    deactivate(pending).andExpect(status().isNoContent());
    reactivate(pending)
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.status").value("PENDING_ACTIVATION"))
        .andExpect(jsonPath("$.hasLoginAccess").value(false));
  }

  @Test
  void anUnknownGuestIsNotFound() throws Exception {
    for (String action : new String[] {"/deactivate", "/reactivate"}) {
      mockMvc
          .perform(MockMvcRequestBuilders.post(GUESTS + UUID.randomUUID() + action).cookie(admin()))
          .andExpect(status().isNotFound())
          .andExpect(satisfiesTheContract());
    }
  }

  @Test
  void onlyTheAdministratorDeactivatesOrReactivates() throws Exception {
    terminateTheContract();
    for (String action : new String[] {"/deactivate", "/reactivate"}) {
      String url = GUESTS + id(IdKind.GUEST, temporaryGuest) + action;
      mockMvc
          .perform(MockMvcRequestBuilders.post(url).cookie(guest()))
          .andExpect(status().isForbidden())
          .andExpect(satisfiesTheContract());
      mockMvc
          .perform(MockMvcRequestBuilders.post(url))
          .andExpect(status().isUnauthorized())
          .andExpect(withoutSession());
    }
    assertThat(guestRow(temporaryGuest)).containsEntry("status", "ACTIVE");
  }

  private static ResultMatcher withoutSession() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule(
                        "the request has no session on purpose",
                        WhitelistRules.allOf(
                            WhitelistRules.isRequest(),
                            WhitelistRules.messageHasKey("validation.request.security.missing")))));
  }

  private void terminateTheContract() {
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = now() WHERE id = ?", contract);
  }

  private ResultActions deactivate(Long guestId) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post(GUESTS + id(IdKind.GUEST, guestId) + "/deactivate")
            .cookie(admin()));
  }

  private ResultActions reactivate(Long guestId) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post(GUESTS + id(IdKind.GUEST, guestId) + "/reactivate")
            .cookie(admin()));
  }

  private Map<String, Object> guestRow(Long guestId) {
    return jdbc.queryForMap(
        "SELECT status, status_before_inactive FROM guests WHERE id = ?", guestId);
  }

  private String accountStatus(Long accountId) {
    return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, accountId);
  }
}
