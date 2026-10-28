package com.tarimatwasi.quipu.auth.infrastructure.adapter.out.mail;

import com.tarimatwasi.quipu.shared.adapter.out.email.ResendProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls Resend (free plan) to send the recovery email. It is its own bean so that {@code @Async}
 * goes through the Spring proxy: the request that asked for the email does not wait for the
 * provider, so every answer of "forgot password" takes the same time whether the account exists or
 * not (ADR-F4). The link points to the frontend, whose origin is the one CORS already trusts
 * ({@code app.cors.allowed-origin}).
 */
@Component
@Profile("!local")
public class ResendMailSender {

  private static final Logger LOG = LoggerFactory.getLogger(ResendMailSender.class);

  private final RestClient restClient;
  private final String fromEmail;
  private final String frontendOrigin;

  @Autowired
  public ResendMailSender(
      ResendProperties resend, @Value("${app.cors.allowed-origin}") String frontendOrigin) {
    this(
        RestClient.builder().requestFactory(withTimeouts()),
        resend.apiKey(),
        resend.fromEmail(),
        frontendOrigin);
  }

  /** The tests bind a mock server to the builder. */
  ResendMailSender(
      RestClient.Builder builder, String apiKey, String fromEmail, String frontendOrigin) {
    this.restClient =
        builder
            .baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    this.fromEmail = fromEmail;
    this.frontendOrigin = frontendOrigin;
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
  public void send(String toEmail, String code, Duration validFor) {
    String link = ResetLink.of(frontendOrigin, code);
    String text =
        "Hola,\n\n"
            + "Recibimos una solicitud para restablecer tu contraseña de Quipu. Abre este enlace"
            + " para elegir una nueva; vale "
            + validFor.toMinutes()
            + " minutos y solo se puede usar una vez:\n\n"
            + link
            + "\n\n"
            + "Si no lo pediste, ignora este correo: tu contraseña no cambia.\n";
    post(toEmail, "Restablece tu contraseña de Quipu", text, "recovery");
  }

  /**
   * The email with the access of a guest (RF-11), on the same executor and with the same rule: a
   * failure is only logged, without the address, the text (it carries the access) or the provider's
   * answer.
   */
  @Async
  public void sendText(String toEmail, String subject, String text) {
    post(toEmail, subject, text, "access");
  }

  /**
   * The notice to the other administrators that one was created (RF-22), on the same executor and
   * with the same rule: a failure is only logged, without the address, the text or the provider's
   * answer.
   */
  @Async
  public void sendNotice(String toEmail, String subject, String text) {
    post(toEmail, subject, text, "administrator notice");
  }

  /**
   * The link a new administrator uses to choose its first password (RF-22). Unlike the others it
   * runs in the caller's thread, because the administrator who created the account must learn
   * whether it left; the timeouts of the client bound the wait. A failure is logged as the others.
   *
   * @return true if Resend accepted the email
   */
  public boolean sendAdministratorAccessNow(String toEmail, String code, Duration validFor) {
    String link = ResetLink.of(frontendOrigin, code);
    String text =
        "Hola,\n\n"
            + "Se creó tu cuenta de administrador de Quipu. Abre este enlace para elegir tu"
            + " contraseña; vale "
            + validFor.toMinutes()
            + " minutos y solo se puede usar una vez:\n\n"
            + link
            + "\n\n"
            + "Si el enlace vence, usa \"Olvidé mi contraseña\" con este correo para recibir"
            + " otro.\n";
    return post(toEmail, "Tu cuenta de administrador de Quipu", text, "administrator access");
  }

  private boolean post(String toEmail, String subject, String text, String kind) {
    try {
      restClient
          .post()
          .uri("/emails")
          .body(Map.of("from", fromEmail, "to", toEmail, "subject", subject, "text", text))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RestClientException | IllegalArgumentException e) {
      LOG.warn("Resend did not accept the {} email ({})", kind, e.getClass().getSimpleName());
      return false;
    }
  }
}
