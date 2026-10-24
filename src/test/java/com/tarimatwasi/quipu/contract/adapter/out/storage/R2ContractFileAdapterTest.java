package com.tarimatwasi.quipu.contract.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tarimatwasi.quipu.contract.port.out.ContractFileStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.support.SilentServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapter against a local HTTP server that answers like a bucket: no R2, no credentials. */
class R2ContractFileAdapterTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile int status = 200;

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
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopBucket() {
    server.stop(0);
  }

  private R2ContractFileAdapter adapter() {
    return new R2ContractFileAdapter(
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
    adapter.close();
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
    adapter.close();
  }

  @Test
  void aBucketThatAnswersWithAnErrorIsAStorageFailure() {
    status = 500;
    var adapter = adapter();

    assertThatThrownBy(() -> adapter.store(new byte[] {1}))
        .isInstanceOf(ContractFileStorageException.class);
    assertThatThrownBy(() -> adapter.delete("contracts/abc.pdf"))
        .isInstanceOf(ContractFileStorageException.class);
    adapter.close();
  }

  @Test
  void withoutCredentialsTheApplicationStartsAndUsingTheBucketFailsCleanly() {
    var adapter =
        new R2ContractFileAdapter(
            new R2Properties("", "", "", "", "", Duration.ofSeconds(10), Duration.ofSeconds(5)));

    assertThatThrownBy(() -> adapter.store(new byte[] {1}))
        .isInstanceOf(ContractFileStorageException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> adapter.delete("k")).isInstanceOf(ContractFileStorageException.class);
    assertThatThrownBy(() -> adapter.signedUrl("k"))
        .isInstanceOf(ContractFileStorageException.class);
    adapter.close();
  }

  /**
   * TAR-204: a bucket that never answers is a storage failure once the timeout of the call ends.
   */
  @Test
  void aBucketThatNeverAnswersIsAStorageFailureAfterTheTimeout() throws Exception {
    try (var silent = new SilentServer()) {
      var adapter =
          new R2ContractFileAdapter(
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
      adapter.close();
    }
  }
}
