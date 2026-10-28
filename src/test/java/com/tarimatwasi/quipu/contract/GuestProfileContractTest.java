package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.auth.infrastructure.config.ProfileRateLimitProperties;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TAR-222 (CU-20, CU-23, RN-17): the guest edits its own personal and contact data, against the
 * shared contract. {@code contractGuest} (session {@link #guest()}) has a contract and {@code
 * temporaryGuest} (session {@link #temporary()}) is temporary; both completed the onboarding.
 */
class GuestProfileContractTest extends PaymentsContractSupport {

  private static final String CONTRACT_PROFILE = "/bff/guest/profile";
  private static final String TEMPORARY_PROFILE = "/bff/temporary-guest/profile";

  private final Long temporaryAccountId = TestIds.next();

  @Autowired ProfileRateLimitProperties profileRateLimit;

  @BeforeEach
  void personalData() {
    insertAccount(temporaryAccountId, "GUEST", "00000003", temporaryGuest);
    jdbc.update(
        "UPDATE guests SET email = 'ana@example.com', phone = '999111222',"
            + " emergency_contact_name = 'Rosa', emergency_contact_relationship = 'Madre',"
            + " emergency_contact_phone = '988777666' WHERE id = ?",
        contractGuest);
    jdbc.update(
        "UPDATE guests SET full_name = 'Luis Mamani', phone = '955444333' WHERE id = ?",
        temporaryGuest);
  }

  private Cookie temporary() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(temporaryAccountId.toString()));
  }

  private ResultActions edit(String url, Cookie who, String body) throws Exception {
    return mockMvc.perform(
        patch(url).cookie(who).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private Map<String, Object> row(Long guest) {
    return jdbc.queryForMap(
        "SELECT full_name, email, phone, emergency_contact_name, emergency_contact_relationship,"
            + " emergency_contact_phone, document_type, document_number, guest_type, status"
            + " FROM guests WHERE id = ?",
        guest);
  }

  @Test
  void aGuestWithAContractChangesOneFieldAndTheRestKeepTheirValue() throws Exception {
    Map<String, Object> before = row(contractGuest);
    Map<String, Object> other = row(temporaryGuest);

    edit(CONTRACT_PROFILE, guest(), "{\"phone\":\" 977000111 \"}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.id").value(id(IdKind.GUEST, contractGuest)))
        .andExpect(jsonPath("$.name").value("Ana Quispe"))
        .andExpect(jsonPath("$.type").value("CONTRACT"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.documentNumber").value("12345678"));

    Map<String, Object> after = row(contractGuest);
    assertThat(after.get("phone")).isEqualTo("977000111");
    after.remove("phone");
    before.remove("phone");
    assertThat(after).isEqualTo(before);
    assertThat(row(temporaryGuest)).isEqualTo(other);
  }

  @Test
  void aTemporaryGuestChangesEveryFieldButNeitherTheDocumentNorItsStay() throws Exception {
    Map<String, Object> stayBefore = jdbc.queryForMap("SELECT * FROM stays WHERE id = ?", stay);

    edit(
            TEMPORARY_PROFILE,
            temporary(),
            "{\"name\":\"Luis Mamani Q.\",\"phone\":\"944333222\","
                + "\"contactEmail\":\"luis@example.com\","
                + "\"emergencyContact\":{\"name\":\"Eva\",\"relationship\":\" \",\"phone\":\"933\"}}")
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.name").value("Luis Mamani Q."))
        .andExpect(jsonPath("$.type").value("TEMPORARY"));

    assertThat(row(temporaryGuest))
        .containsEntry("full_name", "Luis Mamani Q.")
        .containsEntry("phone", "944333222")
        .containsEntry("email", "luis@example.com")
        .containsEntry("emergency_contact_name", "Eva")
        .containsEntry("emergency_contact_relationship", null)
        .containsEntry("emergency_contact_phone", "933")
        .containsEntry("document_number", "87654321")
        .containsEntry("guest_type", "TEMPORARY")
        .containsEntry("status", "ACTIVE");
    assertThat(jdbc.queryForMap("SELECT * FROM stays WHERE id = ?", stay)).isEqualTo(stayBefore);
  }

  @Test
  void anEmptyBodyIsRefused() throws Exception {
    Map<String, Object> before = row(contractGuest);

    edit(CONTRACT_PROFILE, guest(), "{}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(row(contractGuest)).isEqualTo(before);
  }

  @Test
  void aFieldTheContractDoesNotDefineIsRefusedAndNothingChanges() throws Exception {
    Map<String, Object> before = row(contractGuest);

    edit(CONTRACT_PROFILE, guest(), "{\"phone\":\"977000111\",\"documentNumber\":\"11111111\"}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("documentNumber"));
    // The guest is the one of the session: an id in the body is just another unknown field.
    edit(CONTRACT_PROFILE, guest(), "{\"id\":\"" + id(IdKind.GUEST, temporaryGuest) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("id"));
    edit(CONTRACT_PROFILE, guest(), "{\"emergencyContact\":{\"email\":\"x@example.com\"}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("emergencyContact.email"));
    assertThat(row(contractGuest)).isEqualTo(before);
  }

  @Test
  void aNullContactEmailDoesNotEraseIt() throws Exception {
    edit(CONTRACT_PROFILE, guest(), "{\"contactEmail\":null}")
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("contactEmail"));
    edit(CONTRACT_PROFILE, guest(), "{\"name\":\"   \"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("name"));
    edit(CONTRACT_PROFILE, guest(), "{\"contactEmail\":\"no-es-correo\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.field").value("contactEmail"));

    assertThat(row(contractGuest))
        .containsEntry("email", "ana@example.com")
        .containsEntry("full_name", "Ana Quispe");
  }

  @Test
  void aContactEmailOfAnotherGuestIsTaken() throws Exception {
    jdbc.update("UPDATE guests SET email = 'luis@example.com' WHERE id = ?", temporaryGuest);

    edit(CONTRACT_PROFILE, guest(), "{\"contactEmail\":\"LUIS@example.com\"}")
        .andExpect(status().isConflict())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("GUEST_EMAIL_TAKEN"))
        .andExpect(jsonPath("$.field").value("contactEmail"));
    assertThat(row(contractGuest)).containsEntry("email", "ana@example.com");
    // Its own email again is not taken by anybody else.
    edit(CONTRACT_PROFILE, guest(), "{\"contactEmail\":\"ana@example.com\"}")
        .andExpect(status().isOk());
  }

  @Test
  void eachTypeOfGuestIsRefusedTheProfileOfTheOtherOne() throws Exception {
    Map<String, Object> contractBefore = row(contractGuest);
    Map<String, Object> temporaryBefore = row(temporaryGuest);

    edit(TEMPORARY_PROFILE, guest(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    edit(CONTRACT_PROFILE, temporary(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    assertThat(row(contractGuest)).isEqualTo(contractBefore);
    assertThat(row(temporaryGuest)).isEqualTo(temporaryBefore);
  }

  @Test
  void aGuestThatHasNotCompletedTheOnboardingIsRefused() throws Exception {
    jdbc.update("UPDATE guests SET status = 'ONBOARDING' WHERE id = ?", contractGuest);

    edit(CONTRACT_PROFILE, guest(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_ONBOARDING_REQUIRED"));
    assertThat(row(contractGuest)).containsEntry("phone", "999111222");
  }

  @Test
  void theAdministratorCannotUseEitherRoute() throws Exception {
    edit(CONTRACT_PROFILE, admin(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    edit(TEMPORARY_PROFILE, admin(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    assertThat(row(contractGuest)).containsEntry("phone", "999111222");
    assertThat(row(temporaryGuest)).containsEntry("phone", "955444333");
  }

  /**
   * The limit of the edits of the own profile, through the real chain and with the allowance of the
   * shared context: both routes spend one counter per session user, and the refusal is the 429 of
   * the contract. The refill is greedy, so the loop stops at the first refusal instead of counting.
   */
  @Test
  void theEditsOfTheProfileOfASessionUserAreLimitedWithThe429OfTheContract() throws Exception {
    int allowance = profileRateLimit.profileUpdatesPerMinute();
    int accepted = 0;
    @Nullable MvcResult refused = null;
    for (int sent = 0; sent < allowance * 3 && refused == null; sent++) {
      MvcResult result = edit(CONTRACT_PROFILE, guest(), "{}").andReturn();
      if (result.getResponse().getStatus() == 429) {
        refused = result;
      } else {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        accepted++;
      }
    }

    assertThat(refused).as("the allowance runs out").isNotNull();
    assertThat(accepted).isGreaterThanOrEqualTo(allowance);
    // The other route shares the counter and refuses before the guard of the guest areas.
    edit(TEMPORARY_PROFILE, guest(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isTooManyRequests())
        .andExpect(satisfiesTheContract())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    edit(CONTRACT_PROFILE, guest(), "{\"phone\":\"977000111\"}")
        .andExpect(status().isTooManyRequests())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    assertThat(row(contractGuest)).containsEntry("phone", "999111222");
    // Another session user keeps its own allowance.
    edit(TEMPORARY_PROFILE, temporary(), "{\"phone\":\"944333222\"}").andExpect(status().isOk());
  }
}
