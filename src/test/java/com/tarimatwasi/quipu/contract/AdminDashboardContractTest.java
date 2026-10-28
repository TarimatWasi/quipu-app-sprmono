package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules;
import com.jayway.jsonpath.JsonPath;
import com.tarimatwasi.quipu.shared.masking.IdKind;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * TAR-222 (HU-A04, CU-04): the summary of the home of the administrator against the shared
 * contract. The day is 2026-10-14 in Lima. The fixture has a contract of {@code contractGuest}
 * since 2026-01, due on the 5th with 3 days of grace (October is overdue), and a stay of {@code
 * temporaryGuest} from 2026-10-01 to 2026-10-20 (in course, unpaid).
 */
class AdminDashboardContractTest extends PaymentsContractSupport {

  private static final String DASHBOARD = "/bff/admin/dashboard";

  private ResultActions dashboard() throws Exception {
    return mockMvc.perform(get(DASHBOARD).cookie(admin()));
  }

  private Long insertEnvironment(String code) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, 'ROOM', 'ACTIVE')",
        id,
        code);
    return id;
  }

  private Long guestWithContract(String document, String code, String start, String end) {
    Long guest = insertGuest(document, "CONTRACT", "ACTIVE", null);
    return insertContract(guest, insertEnvironment(code), start, end);
  }

  private Long guestOf(Long contractId) {
    return jdbc.queryForObject(
        "SELECT guest_id FROM contracts WHERE id = ?", Long.class, contractId);
  }

  private Long stayOf(Long guest, String start, String end) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, ?::date, ?::date, 150)",
        id,
        guest,
        start,
        end);
    return id;
  }

  private Map<String, Object> counts() throws Exception {
    String body =
        dashboard()
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$");
  }

  @Test
  void eachActiveContractAndEachStayInCourseCountsInTheStateOfItsAccountStatus() throws Exception {
    // The fixture: its contract is overdue in October (an approved September does not help) and
    // its stay in course is unpaid.
    insertPayment(contractGuest, contract, null, "2026-09", "APPROVED", "2026-09-06T15:00:00Z");
    // Due on the 12th with 5 days of grace: still pending on the 14th.
    Long inGrace = guestWithContract("20000001", "301", "2026-01-01", null);
    jdbc.update("UPDATE contracts SET payment_day = 12, grace_days = 5 WHERE id = ?", inGrace);
    // A voucher under review, after a rejected one.
    Long underReview = guestWithContract("20000002", "302", "2026-03-01", "2027-02-28");
    insertPayment(
        guestOf(underReview), underReview, null, "2026-10", "REJECTED", "2026-10-02T15:00:00Z");
    insertPayment(
        guestOf(underReview), underReview, null, "2026-10", "PENDING", "2026-10-03T15:00:00Z");
    // Paid; it ends today, so it is still active.
    Long paid = guestWithContract("20000003", "303", "2026-06-01", "2026-10-14");
    insertPayment(guestOf(paid), paid, null, "2026-10", "APPROVED", "2026-10-04T15:00:00Z");
    // None of these counts: expired by date, finished by hand, not started yet.
    guestWithContract("20000004", "304", "2026-01-01", "2026-10-13");
    Long finished = guestWithContract("20000005", "305", "2026-01-01", null);
    jdbc.update(
        "UPDATE contracts SET status = 'TERMINATED', terminated_at = now() WHERE id = ?", finished);
    guestWithContract("20000006", "306", "2026-11-01", null);
    // A temporary guest with its stay of today under review counts; the past and future ones not.
    Long reviewing = insertGuest("30000001", "TEMPORARY", "ACTIVE", null);
    Long today = stayOf(reviewing, "2026-10-14", "2026-10-14");
    insertPayment(reviewing, null, today, null, "PENDING", "2026-10-14T13:00:00Z");
    Long gone = insertGuest("30000002", "TEMPORARY", "ACTIVE", null);
    stayOf(gone, "2026-09-01", "2026-10-13");
    Long coming = insertGuest("30000003", "TEMPORARY", "ACTIVE", null);
    stayOf(coming, "2026-10-15", "2026-10-30");

    assertThat(counts())
        .containsEntry("paymentsUpToDate", 1)
        .containsEntry("paymentsUnderReview", 2)
        .containsEntry("paymentsPending", 2)
        .containsEntry("paymentsOverdue", 1)
        .containsEntry("pendingVouchersTotal", 2);
  }

  @Test
  void theStateOfAContractIsTheOneItsAccountStatusShowsForOctober() throws Exception {
    Long inGrace = guestWithContract("20000001", "301", "2026-01-01", null);
    jdbc.update("UPDATE contracts SET payment_day = 12, grace_days = 5 WHERE id = ?", inGrace);
    Long environment =
        jdbc.queryForObject(
            "SELECT environment_id FROM contracts WHERE id = ?", Long.class, inGrace);

    mockMvc
        .perform(
            get("/bff/admin/environments/"
                    + id(IdKind.ENVIRONMENT, environment)
                    + "/account-status")
                .cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[9].period").value("2026-10"))
        .andExpect(jsonPath("$[9].status").value("UNPAID"));
    // The fixture contract (OVERDUE) and the new one (UNPAID), the stay (UNPAID).
    assertThat(counts())
        .containsEntry("paymentsPending", 2)
        .containsEntry("paymentsOverdue", 1)
        .containsEntry("paymentsUpToDate", 0)
        .containsEntry("paymentsUnderReview", 0);
  }

  @Test
  void theVouchersAwaitingReviewAreTheOldestTwentyWithTheirTotal() throws Exception {
    // 22 pending vouchers of one contract, each a month from 2025-01: the newer the month, the
    // older the voucher, so the order is by creation and not by period or id.
    Instant base = Instant.parse("2026-10-10T12:00:00Z");
    List<String> oldestFirst = new ArrayList<>();
    YearMonth month = YearMonth.of(2025, 1);
    for (int i = 0; i < 22; i++, month = month.plusMonths(1)) {
      Long id =
          insertPayment(
              contractGuest,
              contract,
              null,
              month.toString(),
              "PENDING",
              base.minus(i, ChronoUnit.HOURS).toString());
      oldestFirst.add(0, id(IdKind.PAYMENT, id));
    }
    // Neither a resolved voucher nor an approved one waits for the review.
    Long stayGuest = insertGuest("30000001", "TEMPORARY", "ACTIVE", null);
    Long resolved = stayOf(stayGuest, "2026-09-01", "2026-09-05");
    insertPayment(stayGuest, null, resolved, null, "REJECTED", "2026-01-01T00:00:00Z");
    insertPayment(stayGuest, null, resolved, null, "APPROVED", "2026-01-02T00:00:00Z");

    String body =
        dashboard()
            .andExpect(status().isOk())
            .andExpect(satisfiesTheContract())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.pendingVouchersTotal").value(22))
            .andExpect(jsonPath("$.pendingVouchers.length()").value(20))
            .andExpect(jsonPath("$.pendingVouchers[0].status").value("PENDING"))
            .andExpect(
                jsonPath("$.pendingVouchers[0].guestId").value(id(IdKind.GUEST, contractGuest)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    List<String> listed = JsonPath.read(body, "$.pendingVouchers[*].id");
    assertThat(listed).containsExactlyElementsOf(oldestFirst.subList(0, 20));
    assertThat(body).doesNotContain("voucherUrl");
  }

  @Test
  void withNothingToCountEveryFigureIsZeroAndTheListIsEmpty() throws Exception {
    TestTables.clear(jdbc);
    insertAccount(adminId, "ADMIN", "00000001", null);

    dashboard()
        .andExpect(status().isOk())
        .andExpect(satisfiesTheContract())
        .andExpect(
            content()
                .json(
                    "{\"paymentsUpToDate\":0,\"paymentsUnderReview\":0,\"paymentsPending\":0,"
                        + "\"paymentsOverdue\":0,\"pendingVouchers\":[],\"pendingVouchersTotal\":0}",
                    JsonCompareMode.STRICT));
  }

  @Test
  void onlyTheAdministratorReadsIt() throws Exception {
    mockMvc
        .perform(get(DASHBOARD).cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(DASHBOARD))
        .andExpect(status().isUnauthorized())
        .andExpect(
            openApi()
                .isValid(
                    validator(
                        ValidationErrorsWhitelist.create()
                            .withRule(
                                "the request has no session on purpose",
                                WhitelistRules.allOf(
                                    WhitelistRules.isRequest(),
                                    WhitelistRules.messageHasKey(
                                        "validation.request.security.missing"))))));
  }
}
