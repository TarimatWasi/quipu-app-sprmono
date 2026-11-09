package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tarimatwasi.quipu.contract.application.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Clients;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.support.SilentServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The adapter against a local HTTP server that answers like a bucket: no R2, no credentials. */
@ExtendWith(OutputCaptureExtension.class)
class R2ContractFileAdapterTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String body = "";
  private final List<R2Clients> opened = new ArrayList<>();

  @BeforeEach
  void startBucket() throws IOException {
    server =
        HttpServer.create(
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          exchange.getRequestBody().readAllBytes();
          byte[] answer = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
          exchange.getResponseBody().write(answer);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopBucket() {
    opened.forEach(R2Clients::close);
    server.stop(0);
  }

  /** The adapter on its own clients, closed after each test as Spring closes the shared ones. */
  private R2ContractFileAdapter adapterOn(R2Properties properties) {
    var clients = new R2Clients(properties);
    opened.add(clients);
    return new R2ContractFileAdapter(properties, clients);
  }

  private R2ContractFileAdapter adapter() {
    return adapterOn(
        new R2Properties(
            "account",
            "test-access-key",
            "test-secret-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "bucket",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5)));
  }

  @Test
  void aStoredFileGoesToTheBucketUnderAFreshKeyAndCanBeRemoved() {
    var adapter = adapter();

    String key = adapter.store("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
    String other = adapter.store("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
    adapter.delete(key);

    assertThat(key).startsWith("contracts/").endsWith(".pdf").isNotEqualTo(other);
    assertThat(requests)
        .contains("PUT /bucket/" + key, "PUT /bucket/" + other, "DELETE /bucket/" + key);
  }

  @Test
  void aSignedUrlNamesTheObjectExpiresInFifteenMinutesAndCarriesNoSecret() {
    var adapter = adapter();

    String url = adapter.signedUrl("contracts/abc.pdf");

    assertThat(url).contains("/bucket/contracts/abc.pdf").contains("X-Amz-Signature=");
    assertThat(url).contains("X-Amz-Expires=900");
    assertThat(url)
        .as("the file is downloaded, never shown inline, and under a fixed name")
        .contains("response-content-disposition=attachment")
        .contains("contrato.pdf");
    assertThat(url).doesNotContain("test-secret-key");
    assertThat(requests).as("signing is local, nothing is sent").isEmpty();
  }

  @Test
  void aBucketThatAnswersWithAnErrorIsAStorageFailure() {
    status = 500;
    var adapter = adapter();

    assertThatThrownBy(() -> adapter.store(new byte[] {1}))
        .isInstanceOf(ContractFileStorageException.class);
    assertThatThrownBy(() -> adapter.delete("contracts/abc.pdf"))
        .isInstanceOf(ContractFileStorageException.class);
  }

  @Test
  void withoutCredentialsTheApplicationStartsAndUsingTheBucketFailsCleanly() {
    var adapter =
        adapterOn(
            new R2Properties("", "", "", "", "", Duration.ofSeconds(10), Duration.ofSeconds(5)));

    assertThatThrownBy(() -> adapter.store(new byte[] {1}))
        .isInstanceOf(ContractFileStorageException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> adapter.delete("k")).isInstanceOf(ContractFileStorageException.class);
    assertThatThrownBy(() -> adapter.signedUrl("k"))
        .isInstanceOf(ContractFileStorageException.class);
  }

  /**
   * TAR-204: a bucket that never answers is a storage failure once the timeout of the call ends.
   */
  @Test
  void aBucketThatNeverAnswersIsAStorageFailureAfterTheTimeout() throws Exception {
    try (var silent = new SilentServer()) {
      var adapter =
          adapterOn(
              new R2Properties(
                  "account",
                  "test-access-key",
                  "test-secret-key",
                  silent.endpoint(),
                  "bucket",
                  Duration.ofMillis(500),
                  Duration.ofMillis(300)));

      long start = System.nanoTime();
      assertThatThrownBy(() -> adapter.store(new byte[] {1}))
          .isInstanceOf(ContractFileStorageException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
  }

  /** TAR-356: the log says why the bucket refused, never the key nor the host of the bucket. */
  @Test
  void aRefusalOfTheBucketIsLoggedWithItsCodeAndNeverTheKeyNorTheHost(CapturedOutput output) {
    status = 403;
    body = "<Error><Code>InvalidAccessKeyId</Code><Message>refused</Message></Error>";
    var adapter = adapter();

    assertThatThrownBy(() -> adapter.store(new byte[] {1}))
        .isInstanceOf(ContractFileStorageException.class);

    assertThat(output)
        .contains("R2 put of a contract file failed: status 403 InvalidAccessKeyId")
        .doesNotContain("contracts/")
        .doesNotContain("127.0.0.1");
  }

  @Test
  void usingTheBucketWithoutR2IsLogged(CapturedOutput output) {
    var adapter =
        adapterOn(
            new R2Properties("", "", "", "", "", Duration.ofSeconds(10), Duration.ofSeconds(5)));

    assertThatThrownBy(() -> adapter.delete("k")).isInstanceOf(ContractFileStorageException.class);

    assertThat(output).contains("R2 is not configured: the contract file cannot be stored");
  }
}
