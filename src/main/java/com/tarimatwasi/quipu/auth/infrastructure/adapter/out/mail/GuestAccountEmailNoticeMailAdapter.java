package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.GuestAccountEmailNoticePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * TAR-229: the notice to the previous account email of a guest leaves through {@link
 * ResendMailSender} in another thread (ADR-F4), like the notices to the administrators. It shows
 * the new email only partly and nothing that gives access: no link, code or document. The local
 * profile has no provider: nothing is sent.
 */
@Component
public class GuestAccountEmailNoticeMailAdapter implements GuestAccountEmailNoticePort {

  private static final Logger LOG =
      LoggerFactory.getLogger(GuestAccountEmailNoticeMailAdapter.class);

  static final String SUBJECT = "Se cambió el correo de tu cuenta de Quipu";

  private final ObjectProvider<ResendMailSender> sender;

  public GuestAccountEmailNoticeMailAdapter(ObjectProvider<ResendMailSender> sender) {
    this.sender = sender;
  }

  @Override
  public void accountEmailChanged(String previousEmail, String newEmail) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    String text =
        "Hola,\n\n"
            + "El administrador de Quipu cambió el correo de tu cuenta a "
            + masked(newEmail)
            + ". Desde ahora la recuperación de contraseña se envía a ese correo y no a este.\n\n"
            + "Si no pediste este cambio, comunícate con la administración.\n";
    try {
      resend.sendNotice(previousEmail, SUBJECT, text);
    } catch (TaskRejectedException e) {
      // Neither the addresses nor the text: this reaches the logs.
      LOG.warn(
          "Account email notice was not queued: the task executor is at its concurrency limit");
    }
  }

  /** The first character of the name and the domain: {@code a***@example.com}. */
  static String masked(String email) {
    int at = email.lastIndexOf('@');
    if (at < 1 || at == email.length() - 1) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
