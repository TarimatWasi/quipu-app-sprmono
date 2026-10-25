package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.contract.port.out.ContractFilePort;
import com.tarimatwasi.quipu.guest.port.out.GuestDocumentFilePort;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RF-15 through the real servlet container, on every upload route: the contract answers its own
 * code and the vouchers theirs. The routes of the payments have no controller yet: the limit is
 * raised before the dispatcher looks for one.: MockMvc hands the controller an already parsed
 * request, so only a real one shows that a form over the limit is answered with the contract's 400
 * and not with the framework's 413. The guard of the guest areas answers before the form is read
 * (TAR-137), so a guest of the other type or in the onboarding never gets as far as the limit.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class UploadLimitTest {

  private static final String BOUNDARY = "----quipu-limit-test";
  private static final int TEN_MEGABYTES = 10 * 1024 * 1024;
  private static final int TWO_MEGABYTES = 2 * 1024 * 1024;
  private static final Map<String, String> CONTRACT_FIELDS =
      Map.of(
          "monthlyAmount", "850.50",
          "paymentDay", "5",
          "graceDays", "3",
          "startDate", "2026-11-01");

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcTemplate jdbc;
  @Autowired JwtTokenProvider jwtTokenProvider;
  @Autowired IdMasker masker;
  @MockitoBean ContractFilePort files;
  @MockitoBean GuestDocumentFilePort documents;

  private final Long adminId = TestIds.next();
  private final Long contractAccount = TestIds.next();
  private final Long temporaryAccount = TestIds.next();
  private final Long onboardingAccount = TestIds.next();

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status)"
            + " VALUES (?, 'limit@example.test', 'DNI', '00000009', 'hash', 'ADMIN', FALSE,"
            + " 'ACTIVE')",
        adminId);
    insertGuestAccount(contractAccount, "00000010", "CONTRACT", "ACTIVE");
    insertGuestAccount(temporaryAccount, "00000011", "TEMPORARY", "ACTIVE");
    Long onboardingGuest =
        insertGuestAccount(onboardingAccount, "00000012", "CONTRACT", "ONBOARDING");
    jdbc.update(
        "UPDATE guests SET personal_data_completed_at = now() WHERE id = ?", onboardingGuest);
    Mockito.when(documents.store(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn("guest-documents/fake");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  @Test
  void aFormWithAFileOverTenMegabytesIsAnInvalidContractNotA413() throws Exception {
    var response = upload(UploadRoutes.CONTRACTS, adminId, bigPdf(), contractFields());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("\"code\":\"CONTRACT_INVALID_FILE\"")
        .contains("\"field\":\"file\"");
  }

  @Test
  void aFormOverTheLimitOnAVoucherRouteIsAnInvalidVoucher() throws Exception {
    for (String route : UploadRoutes.VOUCHERS) {
      Long account =
          switch (route) {
            case UploadRoutes.ADMIN_PAYMENTS -> adminId;
            case UploadRoutes.GUEST_PAYMENTS -> contractAccount;
            default -> temporaryAccount;
          };

      var response = upload(route, account, bigPdf(), Map.of());

      assertThat(response.statusCode()).as(route).isEqualTo(400);
      assertThat(response.body())
          .as(route)
          .contains("\"code\":\"VOUCHER_INVALID_FILE\"")
          .contains("\"field\":\"file\"")
          .contains("5 MB");
    }
  }

  @Test
  void aDocumentOfTwoMegabytesPassesTheLimitOfTheForm() throws Exception {
    var response =
        upload(
            UploadRoutes.GUEST_ONBOARDING_DOCUMENTS,
            onboardingAccount,
            TestPdfs.ofSize(TWO_MEGABYTES),
            Map.of());

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
  }

  @Test
  void aDocumentOverTheLimitOfTheFormIsAnInvalidDocument() throws Exception {
    var response =
        upload(UploadRoutes.GUEST_ONBOARDING_DOCUMENTS, onboardingAccount, bigPdf(), Map.of());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body())
        .contains("\"code\":\"DOCUMENT_INVALID_FILE\"")
        .contains("\"field\":\"file\"");
  }

  @Test
  void aGuestInTheOnboardingIsSentBackToItBeforeItsUploadIsRead() throws Exception {
    var response = upload(UploadRoutes.GUEST_PAYMENTS, onboardingAccount, bigPdf(), Map.of());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("\"code\":\"AUTH_ONBOARDING_REQUIRED\"");
  }

  @Test
  void aGuestOfTheOtherTypeIsRefusedBeforeItsUploadIsRead() throws Exception {
    var response =
        upload(UploadRoutes.TEMPORARY_GUEST_PAYMENTS, contractAccount, bigPdf(), Map.of());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("\"code\":\"AUTH_FORBIDDEN\"");
  }

  /** TAR-187: the replacement of a document is a PUT, parsed by the real container too. */
  @Test
  void theReplacementOfADocumentIsReadByPutAndOverTheLimitIsAnInvalidDocument() throws Exception {
    Long guest =
        jdbc.queryForObject("SELECT guest_id FROM users WHERE id = ?", Long.class, contractAccount);
    Long document = TestIds.next();
    jdbc.update(
        "INSERT INTO guest_documents (id, guest_id, storage_key, original_name, content_type,"
            + " size_bytes) VALUES (?, ?, 'guest-documents/old', 'd.pdf', 'application/pdf', 10)",
        document,
        guest);
    Mockito.when(documents.signedUrl(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
        .thenReturn("https://r2.test/s");
    String route =
        "/bff/admin/guests/"
            + masker.mask(IdKind.GUEST, guest)
            + "/documents/"
            + masker.mask(IdKind.GUEST_DOCUMENT, document);

    var tooBig = upload("PUT", route, adminId, bigPdf(), Map.of("reason", "UPLOAD_ERROR"));
    assertThat(tooBig.statusCode()).isEqualTo(400);
    assertThat(tooBig.body())
        .contains("\"code\":\"DOCUMENT_INVALID_FILE\"")
        .contains("\"field\":\"file\"");

    var replaced =
        upload(
            "PUT",
            route,
            adminId,
            TestPdfs.ofSize(TWO_MEGABYTES),
            Map.of("reason", "UPLOAD_ERROR"));
    assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
  }

  private Map<String, String> contractFields() {
    var fields = new java.util.LinkedHashMap<>(CONTRACT_FIELDS);
    fields.put("environmentId", masker.mask(IdKind.ENVIRONMENT, 1L).toString());
    fields.put("guestId", masker.mask(IdKind.GUEST, 1L).toString());
    return fields;
  }

  private Long insertGuestAccount(Long account, String document, String type, String status) {
    Long guest = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, ?, ?)",
        guest,
        document,
        type,
        status);
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, ?, 'DNI', ?, 'hash', 'GUEST', ?, FALSE, 'ACTIVE')",
        account,
        document + "@example.test",
        document,
        guest);
    return guest;
  }

  private static byte[] bigPdf() {
    return pdf(TEN_MEGABYTES + 1);
  }

  private static byte[] pdf(int size) {
    byte[] pdf = new byte[size];
    byte[] header = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(header, 0, pdf, 0, header.length);
    return pdf;
  }

  private HttpResponse<String> upload(
      String route, Long account, byte[] pdf, Map<String, String> fields) throws Exception {
    return upload("POST", route, account, pdf, fields);
  }

  private HttpResponse<String> upload(
      String method, String route, Long account, byte[] pdf, Map<String, String> fields)
      throws Exception {
    var body = new ByteArrayOutputStream();
    for (var field : fields.entrySet()) {
      part(body, field.getKey(), field.getValue());
    }
    body.write(
        ("--"
                + BOUNDARY
                + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"big.pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(pdf);
    body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + route))
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .header("Origin", "http://localhost:3000")
            .header("Cookie", "sessionToken=" + jwtTokenProvider.issue(account.toString()))
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
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
