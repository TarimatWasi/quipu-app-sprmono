package com.tarimatwasi.quipu.auth.adapter.out.mail;

import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * The notice to the other administrators (RF-22) leaves through {@link ResendMailSender} in another
 * thread (ADR-F4). It says who created which account and nothing that gives access: no link, code
 * or document. The local profile has no provider: nothing is sent.
 */
@Component
public class AdministratorNoticeMailAdapter implements AdministratorNoticePort {

  private static final Logger LOG = LoggerFactory.getLogger(AdministratorNoticeMailAdapter.class);

  static final String SUBJECT = "Se creó un administrador en Quipu";

  private final ObjectProvider<ResendMailSender> sender;

  public AdministratorNoticeMailAdapter(ObjectProvider<ResendMailSender> sender) {
    this.sender = sender;
  }

  @Override
  public void administratorCreated(
      String toEmail, String creatorEmail, String newAdministratorEmail) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    String text =
        "Hola,\n\n"
            + "El administrador "
            + creatorEmail
            + " creó una cuenta de administrador nueva en Quipu para "
            + newAdministratorEmail
            + ".\n\n"
            + "Si no esperabas este cambio, revísalo con los demás administradores.\n";
    try {
      resend.sendNotice(toEmail, SUBJECT, text);
    } catch (TaskRejectedException e) {
      // Neither the addresses nor the text: this reaches the logs.
      LOG.warn(
          "Administrator notice was not queued: the task executor is at its concurrency limit");
    }
  }
}
