package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
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
import com.tarimatwasi.quipu.auth.infrastructure.adapter.out.security.JwtTokenProvider;
import com.tarimatwasi.quipu.payment.application.port.out.PaymentFilePort;
import com.tarimatwasi.quipu.shared.file.TestPdfs;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.shared.masking.IdMasker;
import com.tarimatwasi.quipu.shared.masking.MaskSession;
import com.tarimatwasi.quipu.support.PaymentRows;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.SessionIds;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
  protected static final byte[] PDF = TestPdfs.plain();
  protected static final String KEY = "vouchers/fake";
  protected static final String SIGNED = "https://r2.test/vouchers/fake?X-Amz-Signature=s";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected JwtTokenProvider jwtTokenProvider;
  @Autowired protected IdMasker masker;

  /**
   * TAR-227: every account of a test signs in to this one session, so the ids of its requests and
   * of its checks are masked alike.
   */
  protected final String sessionId = SessionIds.newSessionId();

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
    return new Cookie("sessionToken", jwtTokenProvider.renew(adminId.toString(), sessionId));
  }

  protected Cookie guest() {
    return new Cookie("sessionToken", jwtTokenProvider.renew(guestAccountId.toString(), sessionId));
  }

  /** The ids as the session of the test shows them. */
  protected MaskSession ids() {
    return masker.session(sessionId);
  }

  protected String id(IdKind kind, Long id) {
    return ids().mask(kind, id).toString();
  }

  protected ResultActions resolve(Long id, String body) throws Exception {
    return mockMvc.perform(
        patch(URL + "/" + ids().mask(IdKind.PAYMENT, id))
            .with(csrf())
            .cookie(admin())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  /** A direct payment form with one file in {@code files} and no other part yet. */
  protected static MockMultipartHttpServletRequestBuilder builder(byte[] content) {
    return multipart(URL)
        .with(csrf())
        .file(new MockMultipartFile("files", "v.pdf", "application/pdf", content));
  }

  /**
   * The JSON part {@code proofs} with one voucher that points to the first file (TAR-239). The
   * amount is written as it comes, so a malformed one makes the part unreadable.
   */
  protected static MockMultipartFile proofs(String amount, @Nullable String method) {
    return proofsPart(
        "[{\"kind\":\"VOUCHER\",\"amount\":"
            + amount
            + (method == null ? "" : ",\"method\":\"" + method + "\"")
            + ",\"fileIndex\":0}]");
  }

  /** The JSON part {@code proofs}, written by hand. */
  protected static MockMultipartFile proofsPart(String json) {
    return new MockMultipartFile(
        "proofs", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The body that approves a payment with every proof verified at its declared amount (RN-16), the
   * ids masked in the session of the test.
   */
  protected String approval(Long paymentId) {
    List<String> verified = new ArrayList<>();
    jdbc.query(
        "SELECT id, declared_amount FROM payment_proofs WHERE payment_id = ? ORDER BY id",
        row -> {
          verified.add(
              "{\"id\":\""
                  + id(IdKind.PAYMENT_PROOF, row.getLong(1))
                  + "\",\"verifiedAmount\":"
                  + row.getBigDecimal(2).toPlainString()
                  + "}");
        },
        paymentId);
    return "{\"status\":\"APPROVED\",\"proofs\":[" + String.join(",", verified) + "]}";
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
            .with(csrf())
            .file(new MockMultipartFile("files", name, "application/pdf", content))
            .file(proofs(amount, "Efectivo"))
            .param("guestId", id(IdKind.GUEST, guestId))
            .param(targetName, target));
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
    PaymentRows.insert(
        jdbc,
        new PaymentRows.Row(
            id,
            guest,
            contractId,
            stayId,
            period == null ? null : period + "-01",
            "800",
            status,
            adminId,
            "GUEST",
            createdDate,
            KEY + "-" + id,
            "application/pdf"));
    return id;
  }
}
