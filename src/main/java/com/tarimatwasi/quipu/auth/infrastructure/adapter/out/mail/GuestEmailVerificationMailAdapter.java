package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.GuestEmailVerificationMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** TAR-349: the email with the code, sent through Resend; without Resend nothing is sent. */
@Component
public class GuestEmailVerificationMailAdapter implements GuestEmailVerificationMailPort {

  private static final Logger LOG =
      LoggerFactory.getLogger(GuestEmailVerificationMailAdapter.class);

  private final ObjectProvider<ResendMailSender> sender;
  private final EmailTemplates templates;

  public GuestEmailVerificationMailAdapter(
      ObjectProvider<ResendMailSender> sender, EmailTemplates templates) {
    this.sender = sender;
    this.templates = templates;
  }

  @Override
  public void send(String email, String code, long validMinutes) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return;
    }
    try {
      resend.sendNotice(email, templates.guestEmailVerification(code, validMinutes));
    } catch (TaskRejectedException e) {
      // Neither the address nor the code: this reaches the logs. The guest asks for another code.
      LOG.warn("Email verification code was not queued: the task executor is at its limit");
    }
  }
}
