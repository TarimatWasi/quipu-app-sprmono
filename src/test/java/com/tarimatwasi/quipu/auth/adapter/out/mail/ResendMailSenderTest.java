package com.tarimatwasi.quipu.auth.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendMailSenderTest {

  private static final String CODE = "abc-DEF_123";

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final ResendMailSender sender =
      new ResendMailSender(
          builder, "re_test_key", "Quipu <no-reply@example.test>", "https://app.example.test");
  private final Logger logger = (Logger) LoggerFactory.getLogger(ResendMailSender.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureTheLog() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(logs);
  }

  @Test
  void sendsTheLinkToTheFrontendInSpanishWithTheValidity() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer re_test_key"))
        .andExpect(jsonPath("$.from").value("Quipu <no-reply@example.test>"))
        .andExpect(jsonPath("$.to").value("guest@example.test"))
        .andExpect(jsonPath("$.subject").value("Restablece tu contraseña de Quipu"))
        .andExpect(
            request -> {
              String body = ((MockClientHttpRequest) request).getBodyAsString();
              assertThat(body)
                  .contains("https://app.example.test/reset-password?code=" + CODE)
                  .contains("30 minutos");
            })
        .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

    sender.send("guest@example.test", CODE, Duration.ofMinutes(30));

    server.verify();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void aTrailingSlashInTheOriginDoesNotDoubleTheSlash() {
    var otherBuilder = RestClient.builder();
    var otherServer = MockRestServiceServer.bindTo(otherBuilder).build();
    var other =
        new ResendMailSender(
            otherBuilder, "re_test_key", "no-reply@example.test", "https://app.example.test/");
    otherServer
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(
            request ->
                assertThat(((MockClientHttpRequest) request).getBodyAsString())
                    .contains("https://app.example.test/reset-password?code=" + CODE))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    other.send("guest@example.test", CODE, Duration.ofMinutes(30));

    otherServer.verify();
  }

  @Test
  void aConnectionFailureIsOnlyLogged() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andRespond(withException(new IOException("connection refused")));

    assertThatCode(() -> sender.send("guest@example.test", CODE, Duration.ofMinutes(30)))
        .doesNotThrowAnyException();

    assertThat(logs.list).singleElement().extracting(ILoggingEvent::getLevel).hasToString("WARN");
  }

  @Test
  void aRejectedEmailIsLoggedWithoutTheAddressOrTheCode() {
    server.expect(requestTo("https://api.resend.com/emails")).andRespond(withServerError());

    assertThatCode(() -> sender.send("guest@example.test", CODE, Duration.ofMinutes(30)))
        .doesNotThrowAnyException();

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .doesNotContain("guest@example.test")
        .doesNotContain(CODE);
  }

  /** RF-22: the link of a new administrator, and whether Resend took it. */
  @Test
  void theAccessOfAnAdministratorIsSentAtOnceAndTellsThatItLeft() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(jsonPath("$.to").value("admin@example.test"))
        .andExpect(jsonPath("$.subject").value("Tu cuenta de administrador de Quipu"))
        .andExpect(
            request ->
                assertThat(((MockClientHttpRequest) request).getBodyAsString())
                    .contains("https://app.example.test/reset-password?code=" + CODE)
                    .contains("30 minutos")
                    .contains("administrador"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    assertThat(
            sender.sendAdministratorAccessNow("admin@example.test", CODE, Duration.ofMinutes(30)))
        .isTrue();

    server.verify();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void anAccessOfAnAdministratorThatResendRefusesTellsThatItDidNotLeave() {
    server.expect(requestTo("https://api.resend.com/emails")).andRespond(withServerError());

    assertThat(
            sender.sendAdministratorAccessNow("admin@example.test", CODE, Duration.ofMinutes(30)))
        .isFalse();

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .doesNotContain("admin@example.test")
        .doesNotContain(CODE);
  }

  @Test
  void theNoticeToTheAdministratorsGoesAsGiven() {
    server
        .expect(requestTo("https://api.resend.com/emails"))
        .andExpect(jsonPath("$.to").value("otra@example.test"))
        .andExpect(jsonPath("$.subject").value("Aviso"))
        .andExpect(jsonPath("$.text").value("Texto"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    sender.sendNotice("otra@example.test", "Aviso", "Texto");

    server.verify();
  }
}
