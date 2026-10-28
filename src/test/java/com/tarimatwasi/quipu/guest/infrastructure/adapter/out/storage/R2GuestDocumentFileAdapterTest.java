package com.tarimatwasi.quipu.guest.infrastructure.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tarimatwasi.quipu.guest.application.port.out.GuestDocumentStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import com.tarimatwasi.quipu.shared.file.FileKind;
import com.tarimatwasi.quipu.support.SilentServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapter against a local HTTP server that answers like a bucket: no R2, no credentials. */
class R2GuestDocumentFileAdapterTest {

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> contentTypes = new CopyOnWriteArrayList<>();
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
          String type = exchange.getRequestHeaders().getFirst("Content-Type");
          if (type != null) {
            contentTypes.add(type);
          }
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

  private R2GuestDocumentFileAdapter adapter() {
    return new R2GuestDocumentFileAdapter(
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
  void aStoredDocumentGoesUnderItsOwnPrefixWithItsTypeAndCanBeRemoved() {
    var adapter = adapter();

    String key = adapter.store(new byte[] {1, 2, 3}, FileKind.PNG);
    String other = adapter.store(new byte[] {1, 2, 3}, FileKind.PDF);
    adapter.delete(key);

    assertThat(key).startsWith("guest-documents/").isNotEqualTo(other);
    assertThat(key).doesNotContain(".");
    assertThat(requests)
        .contains("PUT /bucket/" + key, "PUT /bucket/" + other, "DELETE /bucket/" + key);
    assertThat(contentTypes).contains("image/png", "application/pdf");
    adapter.close();
  }

  @Test
  void aSignedUrlDownloadsTheDocumentUnderAFixedNameForFiveMinutes() {
    var adapter = adapter();

    String url = adapter.signedUrl("guest-documents/abc", FileKind.JPEG);

    assertThat(url).contains("/bucket/guest-documents/abc").contains("X-Amz-Signature=");
    assertThat(url).contains("X-Amz-Expires=300");
    assertThat(url)
        .contains("response-content-disposition=attachment")
        .contains("documento.jpg")
        .contains("response-content-type=image%2Fjpeg");
    assertThat(url).doesNotContain("test-secret-key");
    assertThat(requests).as("signing is local, nothing is sent").isEmpty();
    adapter.close();
  }

  @Test
  void theFixedNameFollowsTheType() {
    var adapter = adapter();

    assertThat(adapter.signedUrl("guest-documents/a", FileKind.PDF)).contains("documento.pdf");
    assertThat(adapter.signedUrl("guest-documents/a", FileKind.PNG)).contains("documento.png");
    adapter.close();
  }

  @Test
  void aBucketThatAnswersWithAnErrorIsAStorageFailure() {
    status = 500;
    var adapter = adapter();

    assertThatThrownBy(() -> adapter.store(new byte[] {1}, FileKind.PDF))
        .isInstanceOf(GuestDocumentStorageException.class);
    assertThatThrownBy(() -> adapter.delete("guest-documents/abc"))
        .isInstanceOf(GuestDocumentStorageException.class);
    adapter.close();
  }

  @Test
  void withoutCredentialsTheApplicationStartsAndUsingTheBucketFailsCleanly() {
    var adapter =
        new R2GuestDocumentFileAdapter(
            new R2Properties("", "", "", "", "", Duration.ofSeconds(10), Duration.ofSeconds(5)));

    assertThatThrownBy(() -> adapter.store(new byte[] {1}, FileKind.PDF))
        .isInstanceOf(GuestDocumentStorageException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> adapter.delete("k")).isInstanceOf(GuestDocumentStorageException.class);
    assertThatThrownBy(() -> adapter.signedUrl("k", FileKind.PDF))
        .isInstanceOf(GuestDocumentStorageException.class);
    adapter.close();
  }

  /**
   * TAR-204: a bucket that never answers is a storage failure once the timeout of the call ends.
   */
  @Test
  void aBucketThatNeverAnswersIsAStorageFailureAfterTheTimeout() throws Exception {
    try (var silent = new SilentServer()) {
      var adapter =
          new R2GuestDocumentFileAdapter(
              new R2Properties(
                  "account",
                  "test-access-key",
                  "test-secret-key",
                  silent.endpoint(),
                  "bucket",
                  Duration.ofMillis(500),
                  Duration.ofMillis(300)));

      long start = System.nanoTime();
      assertThatThrownBy(() -> adapter.store(new byte[] {1}, FileKind.PDF))
          .isInstanceOf(GuestDocumentStorageException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
      adapter.close();
    }
  }
}
