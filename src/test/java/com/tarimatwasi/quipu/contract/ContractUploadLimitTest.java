package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RF-15 through the real servlet container: MockMvc hands the controller an already parsed request,
 * so only a real one shows that a form over the limit is answered with the contract's 400 and not
 * with the framework's 413.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ContractUploadLimitTest {

  private static final String BOUNDARY = "----quipu-limit-test";
  private static final int TEN_MEGABYTES = 10 * 1024 * 1024;

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean ContractFilePort files;

  private final Long adminId = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'limit@example.test', 'DNI', '00000009', 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void aFormWithAFileOverTenMegabytesIsAnInvalidFileNotA413() throws Exception {
    byte[] pdf = new byte[TEN_MEGABYTES + 1];
    byte[] header = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(header, 0, pdf, 0, header.length);

    var response = upload(pdf);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("\"code\":\"CONTRACT_INVALID_FILE\"")
        .contains("\"field\":\"file\"");
  }

  private HttpResponse<String> upload(byte[] pdf) throws Exception {
    var body = new ByteArrayOutputStream();
    part(body, "environmentId", masker.mask(IdKind.ENVIRONMENT, 1L).toString());
    part(body, "guestId", masker.mask(IdKind.GUEST, 1L).toString());
    part(body, "monthlyAmount", "850.50");
    part(body, "paymentDay", "5");
    part(body, "graceDays", "3");
    part(body, "startDate", "2026-11-01");
    body.write(
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"big.pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(pdf);
    body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bff/admin/contracts"))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .header("Origin", "http://localhost:3000")
            .header("Cookie", "sessionToken=" + jwtTokenProvider.issue(adminId.toString()))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static void part(ByteArrayOutputStream body, String name, String value)
      throws java.io.IOException {
    body.write(
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\""
                + name
                + "\"\r\n\r\n"
                + value
                + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
  }
}
