package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort.StatusChange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

/** RF-22: the notice says who created which account and nothing that gives access. */
class AdministratorNoticeMailAdapterTest {

  private final ResendMailSender sender = mock(ResendMailSender.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ResendMailSender> provider = mock(ObjectProvider.class);

  private final AdministratorNoticeMailAdapter adapter =
      new AdministratorNoticeMailAdapter(
          provider,
          new EmailTemplates(
              () -> "https://app.example.test", () -> "https://assets.example.test"));

  @Test
  void tellsWhoCreatedWhichAccount() {
    when(provider.getIfAvailable()).thenReturn(sender);

    adapter.administratorCreated("otra@example.test", "creador@example.test", "nueva@example.test");

    var mail = ArgumentCaptor.forClass(MailContent.class);
    verify(sender).sendNotice(eq("otra@example.test"), mail.capture());
    assertThat(mail.getValue().subject()).isEqualTo("Se creó un administrador en Quipu");
    assertThat(mail.getValue().text())
        .contains("creador@example.test")
        .contains("nueva@example.test")
        .doesNotContain("http")
        .doesNotContain("code");
    assertThat(mail.getValue().html())
        .contains("creador@example.test")
        .contains("nueva@example.test");
  }

  /** TAR-127: who deactivated or reactivated which account, and nothing that gives access. */
  @Test
  void tellsWhoDeactivatedOrReactivatedWhichAccount() {
    when(provider.getIfAvailable()).thenReturn(sender);

    adapter.administratorStatusChanged(
        "otra@example.test",
        "actor@example.test",
        "destino@example.test",
        StatusChange.DEACTIVATED);
    adapter.administratorStatusChanged(
        "otra@example.test",
        "actor@example.test",
        "destino@example.test",
        StatusChange.REACTIVATED);

    var mails = ArgumentCaptor.forClass(MailContent.class);
    verify(sender, times(2)).sendNotice(eq("otra@example.test"), mails.capture());
    MailContent deactivated = mails.getAllValues().get(0);
    MailContent reactivated = mails.getAllValues().get(1);
    assertThat(deactivated.subject()).isEqualTo("Se desactivó un administrador en Quipu");
    assertThat(reactivated.subject()).isEqualTo("Se reactivó un administrador en Quipu");
    assertThat(deactivated.text())
        .contains("actor@example.test desactivó")
        .contains("destino@example.test")
        .contains("ya no puede iniciar sesión")
        .doesNotContain("http")
        .doesNotContain("code");
    assertThat(reactivated.text())
        .contains("actor@example.test reactivó")
        .contains("destino@example.test")
        .contains("puede iniciar sesión de nuevo")
        .doesNotContain("http");
  }

  @Test
  void withoutAProviderNoStatusNoticeIsSent() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThatCode(
            () ->
                adapter.administratorStatusChanged(
                    "otra@example.test",
                    "actor@example.test",
                    "destino@example.test",
                    StatusChange.DEACTIVATED))
        .doesNotThrowAnyException();
  }

  @Test
  void withoutAProviderNothingIsSent() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThatCode(
            () ->
                adapter.administratorCreated(
                    "otra@example.test", "creador@example.test", "nueva@example.test"))
        .doesNotThrowAnyException();
  }

  @Test
  void anExecutorAtItsLimitDropsTheNoticeWithoutTheAddresses() {
    when(provider.getIfAvailable()).thenReturn(sender);
    doThrow(new TaskRejectedException("limit reached"))
        .when(sender)
        .sendNotice(anyString(), any(MailContent.class));
    var logger = (Logger) LoggerFactory.getLogger(AdministratorNoticeMailAdapter.class);
    var logs = new ListAppender<ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThatCode(
              () ->
                  adapter.administratorCreated(
                      "otra@example.test", "creador@example.test", "nueva@example.test"))
          .doesNotThrowAnyException();
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("not queued")
        .doesNotContain("@example.test");
  }
}
