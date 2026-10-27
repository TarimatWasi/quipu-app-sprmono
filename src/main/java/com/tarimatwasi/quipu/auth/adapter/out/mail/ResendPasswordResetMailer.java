package com.tarimatwasi.quipu.auth.adapter.out.mail;

import com.tarimatwasi.quipu.auth.port.out.PasswordResetMailPort;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * The recovery email leaves through Resend in another thread (ADR-F4): this adapter hands it to
 * {@link ResendMailSender} and returns, so the caller never waits for the provider. When the
 * executor of Spring Boot is at its limit ({@code spring.task.execution.simple.concurrency-limit})
 * the email is dropped with a warning; the person asks again once the cooldown passes.
 */
@Component
@Profile("!local")
public class ResendPasswordResetMailer implements PasswordResetMailPort {

  private static final Logger LOG = LoggerFactory.getLogger(ResendPasswordResetMailer.class);

  private final ResendMailSender sender;

  public ResendPasswordResetMailer(ResendMailSender sender) {
    this.sender = sender;
  }

  @Override
  public void sendResetLink(String toEmail, String code, Duration validFor) {
    try {
      sender.send(toEmail, code, validFor);
    } catch (TaskRejectedException e) {
      // Neither the address nor the code: this reaches the logs.
      LOG.warn("Recovery email was not queued: the task executor is at its concurrency limit");
    }
  }

  /** In the caller's thread: the administrator who created the account learns whether it left. */
  @Override
  public boolean sendAccessLinkNow(String toEmail, String code, Duration validFor) {
    return sender.sendAdministratorAccessNow(toEmail, code, validFor);
  }
}
