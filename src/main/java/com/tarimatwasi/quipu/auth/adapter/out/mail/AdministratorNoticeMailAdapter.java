package com.tarimatwasi.quipu.auth.adapter.out.mail;

import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.port.out.AdministratorNoticePort.StatusChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * The notices to the other administrators (RF-22, TAR-127) leave through {@link ResendMailSender}
 * in another thread (ADR-F4). They say who created, deactivated or reactivated which account and
 * nothing that gives access: no link, code or document. The local profile has no provider: nothing
 * is sent.
 */
@Component
public class AdministratorNoticeMailAdapter implements AdministratorNoticePort {

  private static final Logger LOG = LoggerFactory.getLogger(AdministratorNoticeMailAdapter.class);

  static final String SUBJECT = "Se creó un administrador en Quipu";
  static final String DEACTIVATED_SUBJECT = "Se desactivó un administrador en Quipu";
  static final String REACTIVATED_SUBJECT = "Se reactivó un administrador en Quipu";

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
    send(resend, toEmail, SUBJECT, text);
  }

  @Override
  public void administratorStatusChanged(
      String toEmail, String actorEmail, String administratorEmail, StatusChange change) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    boolean deactivated = change == StatusChange.DEACTIVATED;
    String text =
        "Hola,\n\n"
            + "El administrador "
            + actorEmail
            + (deactivated ? " desactivó" : " reactivó")
            + " la cuenta de administrador de "
            + administratorEmail
            + " en Quipu"
            + (deactivated
                ? ": ya no puede iniciar sesión hasta que se reactive.\n\n"
                : ": puede iniciar sesión de nuevo.\n\n")
            + "Si no esperabas este cambio, revísalo con los demás administradores.\n";
    send(resend, toEmail, deactivated ? DEACTIVATED_SUBJECT : REACTIVATED_SUBJECT, text);
  }

  private static void send(ResendMailSender resend, String toEmail, String subject, String text) {
    try {
      resend.sendNotice(toEmail, subject, text);
    } catch (TaskRejectedException e) {
      // Neither the addresses nor the text: this reaches the logs.
      LOG.warn(
          "Administrator notice was not queued: the task executor is at its concurrency limit");
    }
  }
}
