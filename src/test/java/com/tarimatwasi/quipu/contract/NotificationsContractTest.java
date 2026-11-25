package com.tarimatwasi.quipu.contract;

import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamRegistry;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Kind;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.NewNotification;
import com.tarimatwasi.quipu.notification.application.port.in.RaiseNotificationUseCase.Target;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The bell end to end (contract v0.27.0): a fine confirmed by an administrator reaches the bell of
 * its guest through the outbox, with its text written in Spanish when read; the guest stream gets
 * only its bell key; the read state is per account and a notification of another reader answers
 * 404.
 */
@TestPropertySource(
    properties = {
      "app.changes.heartbeat=1ms",
      "app.changes.max-duration=10m",
      "app.changes.notification-keys=true"
    })
class NotificationsContractTest extends PaymentsContractSupport {
  private static final String GUEST_BELL = "/bff/guest/notifications";
  private static final String ADMIN_BELL = "/bff/admin/notifications";
  private static final String TEMPORARY_BELL = "/bff/temporary-guest/notifications";

  @Autowired ChangeStreamRegistry streams;
  @Autowired Clock clock;
  @Autowired RaiseNotificationUseCase raise;

  private final Long temporaryAccountId = TestIds.next();

  @BeforeEach
  void temporaryAccount() {
    insertAccount(temporaryAccountId, "GUEST", "00000003", temporaryGuest);
  }

  private Cookie temporary() {
    return new Cookie(
        "sessionToken", jwtTokenProvider.renew(temporaryAccountId.toString(), sessionId));
  }

  private void fine() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/bff/admin/fines")
                .with(csrf())
                .cookie(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"guestId\":\""
                        + id(IdKind.GUEST, contractGuest)
                        + "\",\"amount\":15,\"month\":\"2026-10\","
                        + "\"description\":\"Ruido después de las 23:00\"}"))
        .andExpect(status().isCreated());
  }

  private String guestBell(String query) throws Exception {
    return mockMvc
        .perform(MockMvcRequestBuilders.get(GUEST_BELL + query).cookie(guest()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String awaitGuestNotification() {
    return Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> guestBell(""), body -> JsonPath.<List<?>>read(body, "$.content").size() == 1);
  }

  @Test
  void appliedFine_reachesTheGuestBellWithASpanishTextAndNoStoredFreeText() throws Exception {
    fine();

    String body = awaitGuestNotification();
    assertThat(JsonPath.<String>read(body, "$.content[0].type")).isEqualTo("FINE_APPLIED");
    assertThat(JsonPath.<String>read(body, "$.content[0].title")).isEqualTo("Nueva multa");
    assertThat(JsonPath.<String>read(body, "$.content[0].body"))
        .isEqualTo("Se registró una multa de S/ 15.00 correspondiente a octubre de 2026.");
    assertThat(JsonPath.<String>read(body, "$.content[0].target.kind")).isEqualTo("FINE");
    assertThat(JsonPath.<Object>read(body, "$.content[0].readAt")).isNull();
    assertThat(JsonPath.<Integer>read(body, "$.unreadCount")).isOne();
    assertThat(JsonPath.<Object>read(body, "$.nextCursor")).isNull();
    assertThat(jdbc.queryForObject("SELECT facts::text FROM notifications", String.class))
        .doesNotContain("Ruido");
  }

  /** A guest sends a proof: the bell of the administrators gets its notification. */
  private String proofReachesTheAdministrators() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/bff/guest/payments")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(guest())
                .file(new MockMultipartFile("files", "proof.pdf", "application/pdf", PDF))
                .file(proofs("900", "Yape"))
                .param("period", "2026-10"))
        .andExpect(status().isCreated());
    String adminBell =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    mockMvc
                        .perform(MockMvcRequestBuilders.get(ADMIN_BELL).cookie(admin()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                body -> JsonPath.<List<?>>read(body, "$.content").size() == 1);
    return adminBell;
  }

  private String adminBell(String query) throws Exception {
    return mockMvc
        .perform(MockMvcRequestBuilders.get(ADMIN_BELL + query).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void paymentCycle_notifiesTheAdministratorsThenTheGuest() throws Exception {
    String adminBell = proofReachesTheAdministrators();
    assertThat(JsonPath.<String>read(adminBell, "$.content[0].type"))
        .isEqualTo("PAYMENT_PROOF_SUBMITTED");
    assertThat(JsonPath.<String>read(adminBell, "$.content[0].body"))
        .isEqualTo("Ana Quispe envió un pago de S/ 900.00 de octubre de 2026 para revisar.");

    Long payment = jdbc.queryForObject("SELECT id FROM payments", Long.class);
    resolve(payment, approval(payment)).andExpect(status().isOk());

    String body = awaitGuestNotification();
    assertThat(JsonPath.<String>read(body, "$.content[0].type")).isEqualTo("PAYMENT_APPROVED");
    assertThat(JsonPath.<String>read(body, "$.content[0].body"))
        .isEqualTo("Tu pago de S/ 900.00 de octubre de 2026 fue aprobado.");
  }

  @Test
  void guestNotification_isNotInTheAdministratorsBell() throws Exception {
    fine();
    awaitGuestNotification();

    mockMvc
        .perform(MockMvcRequestBuilders.get(ADMIN_BELL).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.content").isEmpty())
        .andExpect(jsonPath("$.unreadCount").value(0));
  }

  @Test
  void reading_isIdempotentAndUpdatesTheBadge() throws Exception {
    fine();
    String id = JsonPath.read(awaitGuestNotification(), "$.content[0].id");

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              MockMvcRequestBuilders.post(GUEST_BELL + "/" + id + "/read")
                  .with(csrf())
                  .cookie(guest()))
          .andExpect(status().isNoContent());
    }

    String body = guestBell("");
    assertThat(JsonPath.<Integer>read(body, "$.unreadCount")).isZero();
    assertThat(JsonPath.<Object>read(body, "$.content[0].readAt")).isNotNull();
    assertThat(JsonPath.<List<?>>read(guestBell("?unread=true"), "$.content")).isEmpty();
  }

  @Test
  void readAll_clearsTheBadge() throws Exception {
    fine();
    awaitGuestNotification();

    mockMvc
        .perform(MockMvcRequestBuilders.post(GUEST_BELL + "/read-all").with(csrf()).cookie(guest()))
        .andExpect(status().isNoContent());

    assertThat(JsonPath.<Integer>read(guestBell(""), "$.unreadCount")).isZero();
  }

  @Test
  void anAdministratorReadsItsBellOneByOneOrAllAtOnce() throws Exception {
    String id = JsonPath.read(proofReachesTheAdministrators(), "$.content[0].id");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post(ADMIN_BELL + "/" + id + "/read")
                .with(csrf())
                .cookie(admin()))
        .andExpect(status().isNoContent());
    assertThat(JsonPath.<Integer>read(adminBell(""), "$.unreadCount")).isZero();
    assertThat(JsonPath.<List<?>>read(adminBell("?unread=true"), "$.content")).isEmpty();

    mockMvc
        .perform(MockMvcRequestBuilders.post(ADMIN_BELL + "/read-all").with(csrf()).cookie(admin()))
        .andExpect(status().isNoContent());
    assertThat(JsonPath.<Object>read(adminBell(""), "$.content[0].readAt")).isNotNull();
  }

  /** A real id of the bell of a guest is, for an administrator, a notification it does not have. */
  @Test
  void theNotificationOfAGuest_isNotFoundForAnAdministrator() throws Exception {
    fine();
    awaitGuestNotification();
    Long guestNotification = jdbc.queryForObject("SELECT id FROM notifications", Long.class);

    mockMvc
        .perform(
            MockMvcRequestBuilders.post(
                    ADMIN_BELL + "/" + id(IdKind.NOTIFICATION, guestNotification) + "/read")
                .with(csrf())
                .cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("La notificación no existe"));
  }

  @Test
  void unknownNotification_isNotFound() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post(ADMIN_BELL + "/" + UUID.randomUUID() + "/read")
                .with(csrf())
                .cookie(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void guestStream_receivesOnlyItsBellKey() throws Exception {
    try (var own =
        streams.reserve(
            guestAccountId, "guest", 0, false, ids(), clock.instant().plusSeconds(300))) {
      own.activate(contractGuest);

      fine();

      String frame =
          Awaitility.await()
              .atMost(Duration.ofSeconds(10))
              .until(own::next, next -> next.startsWith("event: changed"));
      assertThat(frame).contains("guest.notifications").doesNotContain("admin.");
    }
  }

  /** The bell of a temporary guest lives in its own area (v0.30.0) and works like the other. */
  @Test
  void temporaryGuest_readsItsBellInItsOwnArea() throws Exception {
    Cookie temporary = temporary();
    mockMvc
        .perform(
            MockMvcRequestBuilders.multipart("/bff/temporary-guest/payments")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(temporary)
                .file(new MockMultipartFile("files", "v.pdf", "application/pdf", PDF))
                .file(proofs("150", null)))
        .andExpect(status().isCreated());
    Long payment = jdbc.queryForObject("SELECT id FROM payments", Long.class);
    resolve(payment, approval(payment)).andExpect(status().isOk());

    String body =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> temporaryBell(temporary),
                page -> JsonPath.<List<?>>read(page, "$.content").size() == 1);
    assertThat(JsonPath.<String>read(body, "$.content[0].type")).isEqualTo("PAYMENT_APPROVED");
    assertThat(JsonPath.<String>read(body, "$.content[0].target.kind")).isEqualTo("PAYMENT");
    assertThat(JsonPath.<Integer>read(body, "$.unreadCount")).isOne();
    String id = JsonPath.read(body, "$.content[0].id");

    mockMvc
        .perform(
            MockMvcRequestBuilders.post(TEMPORARY_BELL + "/" + id + "/read")
                .with(csrf())
                .cookie(temporary))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            MockMvcRequestBuilders.post(TEMPORARY_BELL + "/read-all")
                .with(csrf())
                .cookie(temporary))
        .andExpect(status().isNoContent());
    assertThat(JsonPath.<Integer>read(temporaryBell(temporary), "$.unreadCount")).isZero();
  }

  private String temporaryBell(Cookie temporary) throws Exception {
    return mockMvc
        .perform(MockMvcRequestBuilders.get(TEMPORARY_BELL).cookie(temporary))
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** Each guest type reads the bell only in the area of its type. */
  @Test
  void eachGuestType_isRefusedInTheBellOfTheOtherArea() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.get(GUEST_BELL).cookie(temporary()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(MockMvcRequestBuilders.get(TEMPORARY_BELL).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  /** A document lives under its guest: the target names both (v0.30.0). */
  @Test
  void uploadedDocument_targetsTheDocumentAndItsGuest() throws Exception {
    Long document = TestIds.next();
    raise.raise(
        new NewNotification(
            UUID.randomUUID(),
            Kind.GUEST_DOCUMENT_UPLOADED,
            contractGuest,
            Target.DOCUMENT,
            document,
            Map.of(),
            clock.instant()));

    String body = adminBell("");
    assertThat(JsonPath.<String>read(body, "$.content[0].target.kind")).isEqualTo("DOCUMENT");
    assertThat(JsonPath.<String>read(body, "$.content[0].target.id"))
        .isEqualTo(id(IdKind.GUEST_DOCUMENT, document));
    assertThat(JsonPath.<String>read(body, "$.content[0].target.guestId"))
        .isEqualTo(id(IdKind.GUEST, contractGuest));
  }

  /** Only DOCUMENT names a guest in its target; the other kinds leave the field out. */
  @Test
  void otherTargets_leaveTheGuestOut() throws Exception {
    String adminBell = proofReachesTheAdministrators();
    assertThat(JsonPath.<Map<String, Object>>read(adminBell, "$.content[0].target"))
        .containsOnlyKeys("kind", "id");
  }
}
