package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.AccessMailPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * The activation link points to the frontend, whose origin is the one CORS already trusts ({@code
 * app.cors.allowed-origin}), like the recovery link. The email leaves through {@link
 * ResendMailSender} in another thread (ADR-F4). The local profile has no provider: nothing is sent
 * and, unlike the recovery link, nothing is written to the console either, because the access is
 * already in the answer the administrator receives.
 */
@Component
public class AccessMailAdapter implements AccessMailPort {

  private static final Logger LOG = LoggerFactory.getLogger(AccessMailAdapter.class);

  private final ObjectProvider<ResendMailSender> sender;
  private final String frontendOrigin;

  public AccessMailAdapter(
      ObjectProvider<ResendMailSender> sender,
      @Value("${app.cors.allowed-origin}") String frontendOrigin) {
    this.sender = sender;
    this.frontendOrigin = frontendOrigin;
  }

  @Override
  public String activationUrl(String token) {
    // In the fragment, not the query: the browser never sends it, so it reaches no Referer nor the
    // access logs of the hosting.
    return frontendOrigin.replaceAll("/+$", "") + "/activate#token=" + token;
  }

  @Override
  public boolean send(String toEmail, String subject, String text) {
    ResendMailSender resend = sender.getIfAvailable();
    if (resend == null) {
      return false;
    }
    try {
      resend.sendText(toEmail, subject, text);
      return true;
    } catch (TaskRejectedException e) {
      // Neither the address nor the text (it carries the access): this reaches the logs.
      LOG.warn("Access email was not queued: the task executor is at its concurrency limit");
      return false;
    }
  }
}
