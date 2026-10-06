package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

/**
 * SEC-03: in dev and prod TLS ends at the proxy, so Strict-Transport-Security depends on the
 * embedded container turning {@code X-Forwarded-Proto} into the scheme of the request ({@code
 * server.forward-headers-strategy: native}). The requests here go through the real container with
 * that setting, and the profile files must declare it.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "server.forward-headers-strategy=native")
@ImportTestcontainers(PostgresContainers.class)
class HstsBehindProxyTest {

  private static final String HSTS = "Strict-Transport-Security";

  @Value("${local.server.port}")
  int port;

  @Test
  void hstsIsSentWhenTheProxyForwardsHttps() throws Exception {
    var response = health("X-Forwarded-Proto", "https");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue(HSTS))
        .hasValueSatisfying(
            v -> {
              assertThat(v).startsWith("max-age=31536000");
            });
  }

  @Test
  void hstsIsNotSentWhenTheProxyForwardsHttpOrNothing() throws Exception {
    assertThat(health("X-Forwarded-Proto", "http").headers().firstValue(HSTS)).isEmpty();
    assertThat(health("X-Test", "no-forwarding").headers().firstValue(HSTS)).isEmpty();
  }

  @Test
  void devAndProdDeclareTheForwardedHeadersStrategy() throws IOException {
    for (var profile : new String[] {"dev", "prod"}) {
      var yml = Files.readString(Path.of("src/main/resources/application-" + profile + ".yml"));

      assertThat(yml)
          .as(profile)
          .containsPattern("(?m)^\\s*forward-headers-strategy:\\s*native\\b");
    }
  }

  private HttpResponse<String> health(String header, String value) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
            .header(header, value)
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
