package com.tarimatwasi.quipu.auth.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort.StatusChange;
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
      new AdministratorNoticeMailAdapter(provider);

  @Test
  void tellsWhoCreatedWhichAccount() {
    when(provider.getIfAvailable()).thenReturn(sender);

    adapter.administratorCreated("otra@example.test", "creador@example.test", "nueva@example.test");

    var text = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendNotice(
            eq("otra@example.test"), eq(AdministratorNoticeMailAdapter.SUBJECT), text.capture());
    assertThat(text.getValue())
        .contains("creador@example.test")
        .contains("nueva@example.test")
        .doesNotContain("http")
        .doesNotContain("code");
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

    var deactivated = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendNotice(
            eq("otra@example.test"),
            eq(AdministratorNoticeMailAdapter.DEACTIVATED_SUBJECT),
            deactivated.capture());
    assertThat(deactivated.getValue())
        .contains("actor@example.test desactivó")
        .contains("destino@example.test")
        .contains("ya no puede iniciar sesión")
        .doesNotContain("http")
        .doesNotContain("code");
    var reactivated = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendNotice(
            eq("otra@example.test"),
            eq(AdministratorNoticeMailAdapter.REACTIVATED_SUBJECT),
            reactivated.capture());
    assertThat(reactivated.getValue())
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
        .sendNotice(anyString(), anyString(), anyString());
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
