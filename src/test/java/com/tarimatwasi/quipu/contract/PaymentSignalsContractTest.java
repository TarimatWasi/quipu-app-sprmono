package com.tarimatwasi.quipu.contract;

import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamRegistry;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.SseFrames;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {"app.cors.allowed-origin=http://localhost:3000", "app.changes.heartbeat=2ms"})
class PaymentSignalsContractTest extends PaymentsContractSupport {
  @Autowired ChangeStreamRegistry streams;
  @Autowired Clock clock;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  private ChangeStreamRegistry.Subscription listen(boolean administrator) {
    var listener =
        streams.reserve(
            administrator ? adminId : guestAccountId,
            "test",
            0,
            administrator,
            ids(),
            clock.instant().plusSeconds(60));
    listener.activate(administrator ? null : contractGuest);
    return listener;
  }

  private void upload() throws Exception {
    mockMvc
        .perform(
            multipart("/bff/guest/payments")
                .with(csrf())
                .header("Origin", ORIGIN)
                .cookie(guest())
                .file(new MockMultipartFile("files", "proof.pdf", "application/pdf", PDF))
                .file(proofs("900", "Yape"))
                .param("period", "2026-10"))
        .andExpect(status().isCreated());
  }

  @Test
  void changed_voucherApprovalTerminationAndRefund_publishesCommittedScopes() throws Exception {
    try (var admin = listen(true);
        var guest = listen(false)) {
      upload();
      Long payment = jdbc.queryForObject("SELECT id FROM payments", Long.class);
      String queue = admin.next();
      assertThat(queue).contains("admin.dashboard", "admin.payments.queue");
      SseFrames.assertValid(queue, "Admin");
      SseFrames.assertValid(guest.next(), "Guest");
      resolve(payment, approval(payment)).andExpect(status().isOk());
      assertThat(admin.next()).contains("admin.finance", "admin.credits");
      assertThat(guest.next()).contains("guest.home", "guest.payments");
      mockMvc
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/bff/admin/contracts/" + id(IdKind.CONTRACT, contract) + "/terminate")
                  .with(csrf())
                  .cookie(admin()))
          .andExpect(status().isNoContent());
      assertThat(admin.next()).contains("admin.contracts", "admin.environments");
      assertThat(guest.next()).contains("guest.contract");
      mockMvc
          .perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/bff/admin/refunds")
                  .with(csrf())
                  .cookie(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"guestId\":\""
                          + id(IdKind.GUEST, contractGuest)
                          + "\",\"amount\":100,\"date\":\"2026-10-14\",\"method\":\"Cash\"}"))
          .andExpect(status().isCreated());
      String refund = admin.next();
      assertThat(refund)
          .contains(
              "admin.refunds", "admin.finance", "admin.payment:" + id(IdKind.PAYMENT, payment));
      SseFrames.assertValid(refund, "Admin");
      String own = guest.next();
      assertThat(own).contains("guest.payments", "guest.payment:" + id(IdKind.PAYMENT, payment));
      SseFrames.assertValid(own, "Guest");
    }
  }

  @Test
  void changed_outerRollback_persistsAndPublishesNothing() throws Exception {
    try (var admin = listen(true);
        var guest = listen(false)) {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              tx -> {
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(this::upload);
                tx.setRollbackOnly();
              });
      assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
      assertThat(admin.next()).isEqualTo(": heartbeat\n\n");
      assertThat(guest.next()).isEqualTo(": heartbeat\n\n");
    }
  }

  @Test
  void changed_directPayment_publishesFinancialScope() throws Exception {
    try (var admin = listen(true);
        var guest = listen(false)) {
      mockMvc
          .perform(
              builder(PDF)
                  .header("Origin", ORIGIN)
                  .cookie(admin())
                  .param("guestId", id(IdKind.GUEST, contractGuest))
                  .param("period", "2026-10")
                  .file(proofs("800", "Yape")))
          .andExpect(status().isCreated());
      assertThat(admin.next()).contains("admin.finance", "admin.payments.queue");
      assertThat(guest.next()).contains("guest.home", "guest.payments");
    }
  }
}
