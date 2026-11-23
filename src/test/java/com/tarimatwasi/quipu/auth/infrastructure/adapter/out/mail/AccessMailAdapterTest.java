package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.shared.access.AccessMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

/** RF-11: the email with the access of a guest, a link or a temporary password. */
class AccessMailAdapterTest {

  private final ResendMailSender sender = mock(ResendMailSender.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<ResendMailSender> provider = mock(ObjectProvider.class);

  private final AccessMailAdapter adapter =
      new AccessMailAdapter(
          provider,
          new EmailTemplates(() -> "https://app.example.test", () -> "https://assets.example.test"),
          () -> "https://app.example.test/");

  @Test
  void theActivationLinkCarriesTheTokenInTheFragment() {
    assertThat(adapter.activationUrl("TOKEN"))
        .isEqualTo("https://app.example.test/activate#token=TOKEN");
  }

  @Test
  void aLinkAndATemporaryPasswordEachGoInTheirOwnEmail() {
    when(provider.getIfAvailable()).thenReturn(sender);

    assertThat(adapter.send("g@example.test", AccessMode.LINK, "https://x/activate#token=T"))
        .isTrue();
    assertThat(adapter.send("g@example.test", AccessMode.TEMPORARY_PASSWORD, "Pass-123")).isTrue();

    var mail = ArgumentCaptor.forClass(MailContent.class);
    verify(sender, times(2)).sendAccess(eq("g@example.test"), mail.capture());
    assertThat(mail.getAllValues().get(0).text()).contains("https://x/activate#token=T");
    assertThat(mail.getAllValues().get(1).text()).contains("Pass-123");
  }

  /** Without a provider configured, or at the limit of the executor, nothing left. */
  @Test
  void withoutAProviderOrAtTheLimitNothingLeft() {
    when(provider.getIfAvailable()).thenReturn(null);
    assertThat(adapter.send("g@example.test", AccessMode.LINK, "x")).isFalse();

    when(provider.getIfAvailable()).thenReturn(sender);
    doThrow(new TaskRejectedException("full")).when(sender).sendAccess(any(), any());
    assertThat(adapter.send("g@example.test", AccessMode.LINK, "x")).isFalse();
  }
}
