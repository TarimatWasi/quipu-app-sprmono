package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort;
import com.tarimatwasi.quipu.auth.application.port.out.AdministratorNoticePort.StatusChange;
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

  private final ObjectProvider<ResendMailSender> sender;
  private final EmailTemplates templates;

  public AdministratorNoticeMailAdapter(
      ObjectProvider<ResendMailSender> sender, EmailTemplates templates) {
    this.sender = sender;
    this.templates = templates;
  }

  @Override
  public void administratorCreated(
      String toEmail, String creatorEmail, String newAdministratorEmail) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    send(resend, toEmail, templates.administratorCreated(creatorEmail, newAdministratorEmail));
  }

  @Override
  public void administratorStatusChanged(
      String toEmail, String actorEmail, String administratorEmail, StatusChange change) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    boolean deactivated = change == StatusChange.DEACTIVATED;
    send(
        resend,
        toEmail,
        templates.administratorStatusChanged(actorEmail, administratorEmail, deactivated));
  }

  private static void send(ResendMailSender resend, String toEmail, MailContent mail) {
    try {
      resend.sendNotice(toEmail, mail);
    } catch (TaskRejectedException e) {
      // Neither the addresses nor the text: this reaches the logs.
      LOG.warn(
          "Administrator notice was not queued: the task executor is at its concurrency limit");
    }
  }
}
