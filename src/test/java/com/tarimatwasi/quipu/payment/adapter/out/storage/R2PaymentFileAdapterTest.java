package com.tarimatwasi.quipu.payment.adapter.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tarimatwasi.quipu.payment.domain.VoucherType;
import com.tarimatwasi.quipu.payment.port.out.PaymentFileStorageException;
import com.tarimatwasi.quipu.shared.adapter.out.storage.R2Properties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The adapter against a local HTTP server that answers like a bucket: no R2, no credentials. */
class R2PaymentFileAdapterTest {

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

  private R2PaymentFileAdapter adapter() {
    return new R2PaymentFileAdapter(
        new R2Properties(
            "account",
            "test-access-key",
            "test-secret-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "bucket"));
  }

  @Test
  void aStoredVoucherGoesUnderTheVouchersPrefixWithItsTypeAndCanBeRemoved() {
    var adapter = adapter();

    String key = adapter.store(new byte[] {1, 2, 3}, VoucherType.PNG);
    String other = adapter.store(new byte[] {1, 2, 3}, VoucherType.PDF);
    adapter.delete(key);

    assertThat(key).startsWith("vouchers/").isNotEqualTo(other);
    assertThat(key).doesNotContain(".");
    assertThat(requests)
        .contains("PUT /bucket/" + key, "PUT /bucket/" + other, "DELETE /bucket/" + key);
    assertThat(contentTypes).contains("image/png", "application/pdf");
    adapter.close();
  }

  @Test
  void aSignedUrlDownloadsTheVoucherUnderAFixedNameForFifteenMinutes() {
    var adapter = adapter();

    String url = adapter.signedUrl("vouchers/abc", VoucherType.JPEG);

    assertThat(url).contains("/bucket/vouchers/abc").contains("X-Amz-Signature=");
    assertThat(url).contains("X-Amz-Expires=900");
    assertThat(url)
        .contains("response-content-disposition=attachment")
        .contains("comprobante.jpg")
        .contains("response-content-type=image%2Fjpeg");
    assertThat(url).doesNotContain("test-secret-key");
    assertThat(requests).as("signing is local, nothing is sent").isEmpty();
    adapter.close();
  }

  @Test
  void theFixedNameFollowsTheType() {
    var adapter = adapter();

    assertThat(adapter.signedUrl("vouchers/a", VoucherType.PDF)).contains("comprobante.pdf");
    assertThat(adapter.signedUrl("vouchers/a", VoucherType.PNG)).contains("comprobante.png");
    adapter.close();
  }

  @Test
  void aBucketThatAnswersWithAnErrorIsAStorageFailure() {
    status = 500;
    var adapter = adapter();

    assertThatThrownBy(() -> adapter.store(new byte[] {1}, VoucherType.PDF))
        .isInstanceOf(PaymentFileStorageException.class);
    assertThatThrownBy(() -> adapter.delete("vouchers/abc"))
        .isInstanceOf(PaymentFileStorageException.class);
    adapter.close();
  }

  @Test
  void withoutCredentialsTheApplicationStartsAndUsingTheBucketFailsCleanly() {
    var adapter = new R2PaymentFileAdapter(new R2Properties("", "", "", "", ""));

    assertThatThrownBy(() -> adapter.store(new byte[] {1}, VoucherType.PDF))
        .isInstanceOf(PaymentFileStorageException.class)
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> adapter.delete("k")).isInstanceOf(PaymentFileStorageException.class);
    assertThatThrownBy(() -> adapter.signedUrl("k", VoucherType.PDF))
        .isInstanceOf(PaymentFileStorageException.class);
    adapter.close();
  }
}
