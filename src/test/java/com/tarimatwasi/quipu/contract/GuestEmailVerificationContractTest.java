package com.tarimatwasi.quipu.contract;

import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationMailPort;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * TAR-349: a guest changes its one email by confirming a code sent to the new one, against the real
 * database, the real security chain and the shared contract. The email provider is replaced, so the
 * code is read from the call that would have sent it.
 */
class GuestEmailVerificationContractTest extends PaymentsContractSupport {

  private static final String CONTRACT = "/bff/guest/email-verification";
  private static final String TEMPORARY = "/bff/temporary-guest/email-verification";
  private static final String ONBOARDING = "/bff/guest/onboarding/email-verification";
  private static final String CURRENT = "ana@example.com";
  private static final String ADMIN = "admin@example.com";
  private static final String NEW = "ana.nueva@example.com";

  @MockitoBean GuestEmailVerificationMailPort verificationMail;
  @MockitoBean GuestAccountEmailNoticePort notices;

  private final Long temporaryAccountId = TestIds.next();

  @BeforeEach
  void temporaryAccount() {
    reset(verificationMail, notices);
    insertAccount(temporaryAccountId, "GUEST", "00000003", temporaryGuest);
    // The format email of the contract does not take the reserved .test domain of the support.
    jdbc.update("UPDATE users SET email = ? WHERE id = ?", CURRENT, guestAccountId);
    jdbc.update("UPDATE users SET email = ? WHERE id = ?", ADMIN, adminId);
    jdbc.update("UPDATE users SET email = 'luis@example.com' WHERE id = ?", temporaryAccountId);
  }

  private Cookie temporary() {
    return new Cookie(
        "sessionToken", jwtTokenProvider.renew(temporaryAccountId.toString(), sessionId));
  }

  private ResultActions send(String url, Cookie who, String body) throws Exception {
    return mockMvc.perform(
        MockMvcRequestBuilders.post(url)
            .with(csrf())
            .cookie(who)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions request(String url, Cookie who, String email) throws Exception {
    return send(url, who, "{\"email\":\"" + email + "\"}");
  }

  private ResultActions confirm(String url, Cookie who, String code) throws Exception {
    return send(url + "/confirm", who, "{\"code\":\"" + code + "\"}");
  }

  private String sentCode() {
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(verificationMail).send(eq(NEW), code.capture(), anyLong());
    return code.getValue();
  }

  /** Six digits that are not the code sent. */
  private static String wrong(String code) {
    char last = code.charAt(5);
    return code.substring(0, 5) + (last == '9' ? '0' : (char) (last + 1));
  }

  private @Nullable String emailOf(Long account) {
    return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, account);
  }

  /** Lets another code be asked for at once: the clock of the tests does not move. */
  private void sentLongAgo() {
    jdbc.update(
        "UPDATE guest_email_verifications SET last_sent_at = last_sent_at - INTERVAL '2 minutes'");
  }

  @Test
  void aContractGuestChangesItsEmailWithTheCodeAndThePreviousOneIsTold() throws Exception {
    request(CONTRACT, guest(), NEW)
        .andExpect(status().isAccepted())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"));
    String code = sentCode();

    mockMvc
        .perform(get("/bff/guest/profile").cookie(guest()))
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.email").value(CURRENT))
        .andExpect(jsonPath("$.pendingEmail").value(NEW));
    confirm(CONTRACT, guest(), wrong(code))
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_INVALID"))
        .andExpect(jsonPath("$.field").value("code"));
    assertThat(emailOf(guestAccountId)).isEqualTo(CURRENT);

    confirm(CONTRACT, guest(), code)
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"));

    assertThat(emailOf(guestAccountId)).isEqualTo(NEW);
    verify(notices).accountEmailChanged(CURRENT, NEW);
    mockMvc
        .perform(get("/bff/guest/profile").cookie(guest()))
        .andExpect(jsonPath("$.email").value(NEW))
        .andExpect(jsonPath("$.pendingEmail").isEmpty());
  }

  @Test
  void anotherCodeWithinTheMinuteWaitsAndSaysHowLong() throws Exception {
    request(CONTRACT, guest(), NEW).andExpect(status().isAccepted());

    request(CONTRACT, guest(), "otra@example.com")
        .andExpect(status().isTooManyRequests())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Retry-After", "60"))
        .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_COOLDOWN"))
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(jsonPath("$.retryAfterSeconds").value(60));
  }

  @Test
  void aNewRequestReplacesThePendingOneAndItsCode() throws Exception {
    request(CONTRACT, guest(), "primera@example.com").andExpect(status().isAccepted());
    sentLongAgo();
    request(CONTRACT, guest(), NEW).andExpect(status().isAccepted());

    confirm(CONTRACT, guest(), sentCode()).andExpect(status().isNoContent());
    assertThat(emailOf(guestAccountId)).isEqualTo(NEW);
  }

  @Test
  void itsOwnEmailIsUnchangedAndTheEmailOfAnotherAccountIsTaken() throws Exception {
    request(CONTRACT, guest(), CURRENT.toUpperCase(java.util.Locale.ROOT))
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("EMAIL_UNCHANGED"))
        .andExpect(jsonPath("$.field").value("email"));
    request(CONTRACT, guest(), ADMIN.toUpperCase(java.util.Locale.ROOT))
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("ACCOUNT_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("email"));

    verifyNoInteractions(verificationMail);
  }

  @Test
  void anExpiredCodeEndsTheRequest() throws Exception {
    request(CONTRACT, guest(), NEW).andExpect(status().isAccepted());
    String code = sentCode();
    jdbc.update(
        "UPDATE guest_email_verifications SET expires_at = expires_at - INTERVAL '11 minutes'");

    confirm(CONTRACT, guest(), code)
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_EXPIRED"))
        .andExpect(jsonPath("$.field").value("code"));
    confirm(CONTRACT, guest(), code)
        .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_EXPIRED"));
    assertThat(emailOf(guestAccountId)).isEqualTo(CURRENT);
    // The request stays, with the time of its code: the wait for another one still holds.
    request(CONTRACT, guest(), NEW).andExpect(status().isTooManyRequests());
  }

  @Test
  void fiveWrongCodesEndTheRequestEvenForTheRightOne() throws Exception {
    request(CONTRACT, guest(), NEW).andExpect(status().isAccepted());
    String code = sentCode();
    for (int attempt = 0; attempt < 5; attempt++) {
      confirm(CONTRACT, guest(), wrong(code))
          .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_INVALID"));
    }

    confirm(CONTRACT, guest(), code)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_EXPIRED"));
    assertThat(emailOf(guestAccountId)).isEqualTo(CURRENT);
    verify(notices, never()).accountEmailChanged(anyString(), anyString());
  }

  @Test
  void eachTypeOfGuestUsesItsOwnRoute() throws Exception {
    request(TEMPORARY, temporary(), NEW)
        .andExpect(status().isAccepted())
        .andExpect(satisfiesTheContract());
    confirm(TEMPORARY, temporary(), sentCode())
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
    assertThat(emailOf(temporaryAccountId)).isEqualTo(NEW);

    request(CONTRACT, temporary(), "otra@example.com")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    request(TEMPORARY, guest(), "otra@example.com")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void aGuestStillOnboardingUsesTheOnboardingRouteAndSeesThePendingEmail() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ONBOARDING' WHERE id = ?", contractGuest);

    request(CONTRACT, guest(), NEW)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    request(ONBOARDING, guest(), NEW)
        .andExpect(status().isAccepted())
        .andExpect(satisfiesTheContract());

    mockMvc
        .perform(get("/bff/guest/onboarding").cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.personalData.email").value(CURRENT))
        .andExpect(jsonPath("$.pendingEmail").value(NEW));
    confirm(ONBOARDING, guest(), sentCode())
        .andExpect(status().isNoContent())
        .andExpect(satisfiesTheContract());
    assertThat(emailOf(guestAccountId)).isEqualTo(NEW);
  }

  @Test
  void aBodyOutsideTheSchemaIsRefusedOnItsField() throws Exception {
    send(CONTRACT, guest(), "{}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("email"));
    request(CONTRACT, guest(), "no-es-correo")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("email"));
    send(CONTRACT, guest(), "{\"email\":\"" + NEW + "\",\"guestId\":\"1\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("guestId"));
    confirm(CONTRACT, guest(), "12ab56")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("code"));
    send(CONTRACT + "/confirm", guest(), "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("code"));

    verifyNoInteractions(verificationMail);
  }
}
