package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

/** TAR-229: the previous account email learns of the change, without the whole new email. */
class GuestAccountEmailNoticeMailAdapterTest {

  private final ResendMailSender sender = mock(ResendMailSender.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ResendMailSender> provider = mock(ObjectProvider.class);

  private final GuestAccountEmailNoticeMailAdapter adapter =
      new GuestAccountEmailNoticeMailAdapter(provider);

  @Test
  void tellsThePreviousEmailWithTheNewOneOnlyPartlyShown() {
    when(provider.getIfAvailable()).thenReturn(sender);

    adapter.accountEmailChanged("old@example.test", "nuevo.correo@example.org");

    var text = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendNotice(
            eq("old@example.test"), eq(GuestAccountEmailNoticeMailAdapter.SUBJECT), text.capture());
    assertThat(text.getValue())
        .contains("n***@example.org")
        .contains("recuperación de contraseña")
        .doesNotContain("nuevo.correo")
        .doesNotContain("http");
  }

  @Test
  void masksTheNameOfTheEmail() {
    assertThat(GuestAccountEmailNoticeMailAdapter.masked("ana@example.com"))
        .isEqualTo("a***@example.com");
    assertThat(GuestAccountEmailNoticeMailAdapter.masked("a@b.pe")).isEqualTo("a***@b.pe");
    assertThat(GuestAccountEmailNoticeMailAdapter.masked("@example.com")).isEqualTo("***");
    assertThat(GuestAccountEmailNoticeMailAdapter.masked("ana@")).isEqualTo("***");
    assertThat(GuestAccountEmailNoticeMailAdapter.masked("ana")).isEqualTo("***");
  }

  @Test
  void withoutAProviderNothingIsSent() {
    when(provider.getIfAvailable()).thenReturn(null);

    assertThatCode(() -> adapter.accountEmailChanged("old@example.test", "new@example.test"))
        .doesNotThrowAnyException();
  }

  @Test
  void anExecutorAtItsLimitDropsTheNoticeWithoutTheAddresses() {
    when(provider.getIfAvailable()).thenReturn(sender);
    doThrow(new TaskRejectedException("limit reached"))
        .when(sender)
        .sendNotice(anyString(), anyString(), anyString());
    Logger logger = (Logger) LoggerFactory.getLogger(GuestAccountEmailNoticeMailAdapter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThatCode(() -> adapter.accountEmailChanged("old@example.test", "new@example.test"))
          .doesNotThrowAnyException();
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.get(0).getFormattedMessage())
        .doesNotContain("old@example.test")
        .doesNotContain("new@example.test")
        .doesNotContain("n***");
  }
}
