package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.tarimatwasi.quipu.auth.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.payment.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Shared by the tests of the payments of the administrator: the data, the session cookies, the
 * bucket fake and the way to read an answer against the contract. TAR-43: the real answers of the
 * payments of the administrator (RF-05, CU-05, CU-14) satisfy the shared contract, the one of the
 * tag pinned in pom.xml. The bucket is a fake of the port; the day is fixed at 2026-10-14 in Lima.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
@Import(PaymentsFixedDay.class)
abstract class PaymentsContractSupport {

  protected static final String SPEC =
      java.nio.file.Path.of("target/contract/bff.yaml").toAbsolutePath().toString();
  protected static final String URL = "/bff/admin/payments";
  protected static final String ORIGIN = "http://localhost:3000";
  protected static final byte[] PDF = "%PDF-1.7\n%test voucher".getBytes(StandardCharsets.US_ASCII);
  protected static final String KEY = "vouchers/fake";
  protected static final String SIGNED = "https://r2.test/vouchers/fake?X-Amz-Signature=s";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected JwtTokenProvider jwtTokenProvider;
  @Autowired protected IdMasker masker;
  @MockitoBean protected PaymentFilePort files;

  protected final Long adminId = TestIds.next();
  protected final Long guestAccountId = TestIds.next();
  protected Long contractGuest;
  protected Long temporaryGuest;
  protected Long contract;
  protected Long stay;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    reset(files);
    when(files.store(any(), any())).thenReturn(KEY);
    when(files.signedUrl(anyString(), any())).thenReturn(SIGNED);
    insertAccount(adminId, "ADMIN", "00000001", null);
    contractGuest = insertGuest("12345678", "CONTRACT", "ACTIVE", "Ana Quispe");
    temporaryGuest = insertGuest("87654321", "TEMPORARY", "ACTIVE", null);
    insertAccount(guestAccountId, "GUEST", "00000002", contractGuest);
    Long room = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, '201', 'ROOM', 'ACTIVE')",
        room);
    contract = insertContract(contractGuest, room, "2026-01-01", null);
    stay = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, '2026-10-01', '2026-10-20', 150)",
        stay,
        temporaryGuest);
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  /**
   * Validates against the full contract. Since v0.7.2 the oneOf branches of PaymentSummary, Payment
   * and PaymentQueueItem declare {@code additionalProperties: true}, so no rule is ignored.
   */
  protected static OpenApiInteractionValidator validator(ValidationErrorsWhitelist whitelist) {
    return OpenApiInteractionValidator.createFor(SPEC).withWhitelist(whitelist).build();
  }

  protected static ResultMatcher satisfiesTheContract() {
    return openApi().isValid(validator(ValidationErrorsWhitelist.create()));
  }

  /**
   * MockMvc keeps the parts of a multipart request apart from its body: only the answer is checked.
   */
  protected static ResultMatcher answersTheContract() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("MockMvc multipart request", WhitelistRules.isRequest())));
  }

  // ------------------------------------------------------------------ helpers

  protected static ResultMatcher satisfiesTheContractToAnInvalidRequest() {
    return openApi()
        .isValid(
            validator(
                ValidationErrorsWhitelist.create()
                    .withRule("the request is invalid on purpose", WhitelistRules.isRequest())));
  }

  protected Cookie admin() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(adminId.toString()));
  }

  protected Cookie guest() {
    return new Cookie("sessionToken", jwtTokenProvider.issue(guestAccountId.toString()));
  }

  protected String id(IdKind kind, Long id) {
    return masker.mask(kind, id).toString();
  }

  protected ResultActions resolve(Long id, String body) throws Exception {
    return mockMvc.perform(
        patch(URL + "/" + masker.mask(IdKind.PAYMENT, id))
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  protected static MockMultipartHttpServletRequestBuilder builder(byte[] content) {
    return multipart(URL).file(new MockMultipartFile("file", "v.pdf", "application/pdf", content));
  }

  protected ResultActions postForm(MockMultipartHttpServletRequestBuilder request)
      throws Exception {
    return mockMvc.perform(request.header("Origin", ORIGIN).cookie(admin()));
  }

  /** A direct payment of that guest for a period or a stay, with the payment method Efectivo. */
  protected ResultActions post(
      Long guestId, String targetName, String target, String amount, byte[] content, String name)
      throws Exception {
    return postForm(
        multipart(URL)
            .file(new MockMultipartFile("file", name, "application/pdf", content))
            .param("guestId", id(IdKind.GUEST, guestId))
            .param(targetName, target)
            .param("amount", amount)
            .param("paymentMethod", "Efectivo"));
  }

  protected void insertAccount(Long id, String role, String document, @Nullable Long guestId) {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " guest_id, must_change_password, status)"
            + " VALUES (?, ?, 'DNI', ?, 'hash', ?, ?, FALSE, 'ACTIVE')",
        id,
        document + "@example.test",
        document,
        role,
        guestId);
  }

  protected Long insertGuest(String number, String type, String status, @Nullable String name) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, full_name, document_type, document_number, guest_type, status)"
            + " VALUES (?, ?, 'DNI', ?, ?, ?)",
        id,
        name,
        number,
        type,
        status);
    return id;
  }

  protected Long insertContract(Long guest, Long environment, String start, @Nullable String end) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO contracts (id, guest_id, environment_id, start_date, end_date, monthly_amount,"
            + " payment_day, grace_days, file_key, original_name, size_bytes) VALUES (?, ?, ?,"
            + " ?::date, ?::date, 800, 5, 3, 'contracts/a.pdf', 'a.pdf', 10)",
        id,
        guest,
        environment,
        start,
        end);
    return id;
  }

  protected Long insertPayment(
      Long guest,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable String period,
      String status,
      String createdDate) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO payments (id, guest_id, contract_id, stay_id, period, amount, voucher_key,"
            + " original_name, content_type, size_bytes, status, rejection_reason, reviewed_at,"
            + " reviewed_by_id, recorded_by, created_date) VALUES (?, ?, ?, ?, ?::date, 800, ?,"
            + " 'v.pdf', 'application/pdf', 10, ?, CASE WHEN ? = 'REJECTED' THEN 'No coincide' END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE now() END,"
            + " CASE WHEN ? = 'PENDING' THEN NULL ELSE ?::bigint END, 'GUEST', ?::timestamptz)",
        id,
        guest,
        contractId,
        stayId,
        period == null ? null : period + "-01",
        KEY + "-" + id,
        status,
        status,
        status,
        status,
        adminId,
        createdDate);
    return id;
  }
}
