package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.PasswordResetMailPort;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The local profile has no email provider: the link is written in the console so that whoever
 * develops can open it and choose the password of the first ADMIN. Nothing leaves the machine. The
 * email is masked (BE-SPR-OBS-06); the link keeps its code, an exception for the local profile
 * only.
 */
@Component
@Profile("local & !dev & !prod")
public class LoggingPasswordResetMail implements PasswordResetMailPort {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingPasswordResetMail.class);

  private final String frontendOrigin;

  public LoggingPasswordResetMail(@Value("${app.cors.allowed-origin}") String frontendOrigin) {
    this.frontendOrigin = frontendOrigin;
  }

  @Override
  public void sendResetLink(String toEmail, String code, Duration validFor) {
    LOG.info(
        "[local] Link for {} to choose a password (valid {} minutes): {}",
        masked(toEmail),
        validFor.toMinutes(),
        ResetLink.of(frontendOrigin, code));
  }

  /** The same console line: whoever develops opens it to choose the password of the new ADMIN. */
  @Override
  public boolean sendAccessLinkNow(String toEmail, String code, Duration validFor) {
    sendResetLink(toEmail, code, validFor);
    return true;
  }

  /** {@code admin@quipu.local} becomes {@code a***@quipu.local}; no at sign hides it all. */
  private static String masked(String email) {
    int at = email.indexOf('@');
    return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
  }
}
