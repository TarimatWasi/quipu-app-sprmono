package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.auth.application.port.out.FrontendOriginPort;
import com.tarimatwasi.quipu.shared.adapter.out.email.ResendProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls Resend (free plan) to send the emails (HTML with the plain text, TAR-309). It is its own
 * bean so that {@code @Async} goes through the Spring proxy: the request that asked for the email
 * does not wait for the provider, so every answer of "forgot password" takes the same time whether
 * the account exists or not (ADR-F4). The link points to the frontend, whose origin is the one CORS
 * already trusts ({@link FrontendOriginPort}).
 */
@Component
@Profile("!local")
public class ResendMailSender {

  private static final Logger LOG = LoggerFactory.getLogger(ResendMailSender.class);

  private final RestClient restClient;
  private final String fromEmail;
  private final String frontendOrigin;
  private final EmailTemplates templates;

  @Autowired
  public ResendMailSender(
      ResendProperties resend, FrontendOriginPort frontend, EmailTemplates templates) {
    this(
        RestClient.builder().requestFactory(withTimeouts()),
        resend.apiKey(),
        resend.fromEmail(),
        frontend.frontendOrigin(),
        templates);
  }

  /** The tests bind a mock server to the builder. */
  ResendMailSender(
      RestClient.Builder builder,
      String apiKey,
      String fromEmail,
      String frontendOrigin,
      EmailTemplates templates) {
    this.restClient =
        builder
            .baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.fromEmail = fromEmail;
    this.frontendOrigin = frontendOrigin;
    this.templates = templates;
  }

  /** A provider that hangs holds its own thread, never the one of a request. */
  private static ClientHttpRequestFactory withTimeouts() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    return factory;
  }

  /**
   * Runs on the task executor of Spring Boot, so nothing can reach the caller: a failure is only
   * logged (BE-SPR-CON-05), without the address, the code or the provider's answer, which can echo
   * the address. The person asks again once the cooldown passes.
   */
  @Async
  public void send(String toEmail, String code, Duration validFor, boolean activation) {
    String link =
        activation
            ? ResetLink.activation(frontendOrigin, code)
            : ResetLink.of(frontendOrigin, code);
    post(toEmail, templates.recovery(link, validFor.toMinutes()), "recovery");
  }

  /**
   * The email with the access of a guest (RF-11), on the same executor and with the same rule: a
   * failure is only logged, without the address, the text (it carries the access) or the provider's
   * answer.
   */
  @Async
  public void sendAccess(String toEmail, MailContent mail) {
    post(toEmail, mail, "access");
  }

  /**
   * The notice to the other administrators that one was created (RF-22), on the same executor and
   * with the same rule: a failure is only logged, without the address, the text or the provider's
   * answer.
   */
  @Async
  public void sendNotice(String toEmail, MailContent mail) {
    post(toEmail, mail, "administrator notice");
  }

  /**
   * The link a new administrator uses to choose its first password (RF-22). Unlike the others it
   * runs in the caller's thread, because the administrator who created the account must learn
   * whether it left; the timeouts of the client bound the wait. A failure is logged as the others.
   *
   * @return true if Resend accepted the email
   */
  public boolean sendAdministratorAccessNow(String toEmail, String code, Duration validFor) {
    String link = ResetLink.activation(frontendOrigin, code);
    return post(
        toEmail, templates.administratorAccess(link, validFor.toMinutes()), "administrator access");
  }

  private boolean post(String toEmail, MailContent mail, String kind) {
    try {
      restClient
          .post()
          .uri("/emails")
          .body(
              Map.of(
                  "from",
                  fromEmail,
                  "to",
                  toEmail,
                  "subject",
                  mail.subject(),
                  "html",
                  mail.html(),
                  "text",
                  mail.text()))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientException | IllegalArgumentException e) {
      LOG.warn("Resend did not accept the {} email ({})", kind, e.getClass().getSimpleName());
      return false;
    }
  }
}
