package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

/** TAR-349: the code that proves the guest owns its new email. */
class GuestEmailVerificationMailAdapterTest {

  private final ResendMailSender sender = mock(ResendMailSender.class);

  private final ObjectProvider<ResendMailSender> provider = mock();

  private final GuestEmailVerificationMailAdapter adapter =
      new GuestEmailVerificationMailAdapter(
          provider,
          new EmailTemplates(
              () -> "https://app.example.test", () -> "https://assets.example.test"));

  @Test
  void theCodeGoesWithItsValidity() {
    when(provider.getIfAvailable()).thenReturn(sender);

    adapter.send("nuevo@example.test", "482913", 10);

    var mail = ArgumentCaptor.forClass(MailContent.class);
    verify(sender).sendNotice(eq("nuevo@example.test"), mail.capture());
    assertThat(mail.getValue().subject()).isEqualTo("Confirma tu correo de Quipu");
    assertThat(mail.getValue().text()).contains("482913").contains("10 minutos");
    assertThat(mail.getValue().html()).contains("482913");
  }

  /** Without a provider nothing is sent; at the limit the guest just asks for another code. */
  @Test
  void withoutAProviderOrAtTheLimitNothingFails() {
    when(provider.getIfAvailable()).thenReturn(null);
    adapter.send("nuevo@example.test", "482913", 10);
    verifyNoInteractions(sender);

    when(provider.getIfAvailable()).thenReturn(sender);
    doThrow(new TaskRejectedException("full")).when(sender).sendNotice(any(), any());
    assertThatCode(() -> adapter.send("nuevo@example.test", "482913", 10))
        .doesNotThrowAnyException();
  }
}
