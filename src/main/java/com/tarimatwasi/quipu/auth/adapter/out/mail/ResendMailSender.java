package com.tarimatwasi.quipu.auth.adapter.out.mail;

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
                  "Restablece tu contraseña de Quipu",
                  "text",
                  text))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException | IllegalArgumentException e) {
      LOG.warn("Resend did not accept the recovery email ({})", e.getClass().getSimpleName());
    }
  }
}
