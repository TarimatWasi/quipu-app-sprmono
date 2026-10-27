package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.support.TestIds;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * TAR-48 (RF-18): the balance of a month against the shared contract. A contract payment counts in
 * its period, a stay payment in the month its stay starts; only approved payments count. The
 * fixture has a contract of {@code contractGuest} since 2026-01 and a stay of {@code
 * temporaryGuest} from 2026-10-01 to 2026-10-20.
 */
class FinanceSummaryContractTest extends PaymentsContractSupport {

  private static final String FINANCE = "/bff/admin/finance";

  @Test
  void theBalanceAddsOnlyWhatBelongsToTheMonth() throws Exception {
    // Contract payments: the approved one of the period counts; the rest do not.
    paid(contractGuest, contract, null, "2026-10", "APPROVED", "950.10");
    paid(contractGuest, contract, null, "2026-09", "APPROVED", "900.00");
    paid(contractGuest, contract, null, "2026-10", "REJECTED", "222.00");
    Long otherGuest = insertGuest("11223344", "CONTRACT", "ACTIVE", null);
    Long otherContract = insertContract(otherGuest, insertEnvironment("202"), "2026-01-01", null);
    paid(otherGuest, otherContract, null, "2026-10", "PENDING", "111.00");
    // Stay payments: the stay of the fixture starts in October and counts; one that started in
    // September does not, although it was approved in October.
    paid(temporaryGuest, null, stay, null, "APPROVED", "150.20");
    Long otherTemporary = insertGuest("55667788", "TEMPORARY", "ACTIVE", null);
    Long september = insertStay(otherTemporary, "2026-09-25", "2026-10-05");
    Long late = paid(otherTemporary, null, september, null, "APPROVED", "300.00");
    jdbc.update(
        "UPDATE payments SET reviewed_at = '2026-10-03T15:00:00Z',"
            + " created_date = '2026-10-02T15:00:00Z' WHERE id = ?",
        late);
    insertFine(contractGuest, "20.30", "2026-10");
    insertFine(temporaryGuest, "5.00", "2026-10");
    insertFine(contractGuest, "99.00", "2026-09");
    insertExpense("2000.00", "2026-10");
    insertExpense("50.00", "2026-11");

    assertThat(balance("2026-10"))
        .isEqualTo(
            "{\"month\":\"2026-10\",\"totalIncome\":1125.60,\"incomeFromPayments\":1100.30,"
                + "\"incomeFromFines\":25.30,\"totalExpenses\":2000.00,\"net\":-874.40}");
  }

  @Test
  void theOtherMonthsSeeTheirOwnMovements() throws Exception {
    paid(contractGuest, contract, null, "2026-10", "APPROVED", "950.10");
    paid(contractGuest, contract, null, "2026-09", "APPROVED", "900.00");
    Long otherTemporary = insertGuest("55667788", "TEMPORARY", "ACTIVE", null);
    Long september = insertStay(otherTemporary, "2026-09-25", "2026-10-05");
    paid(otherTemporary, null, september, null, "APPROVED", "300.00");
    insertFine(contractGuest, "99.00", "2026-09");
    insertExpense("50.00", "2026-09");

    assertThat(balance("2026-09"))
        .isEqualTo(
            "{\"month\":\"2026-09\",\"totalIncome\":1299.00,\"incomeFromPayments\":1200.00,"
                + "\"incomeFromFines\":99.00,\"totalExpenses\":50.00,\"net\":1249.00}");
  }

  @Test
  void anInactiveGuestAndAnInactiveEnvironmentStillCount() throws Exception {
    Long inactive = insertGuest("99887766", "CONTRACT", "INACTIVE", null);
    Long closedRoom = insertEnvironment("303");
    jdbc.update("UPDATE environments SET status = 'INACTIVE' WHERE id = ?", closedRoom);
    Long oldContract = insertContract(inactive, closedRoom, "2026-01-01", "2026-12-31");
    paid(inactive, oldContract, null, "2026-10", "APPROVED", "700.00");
    insertFine(inactive, "40.00", "2026-10");
    jdbc.update("UPDATE guests SET status = 'INACTIVE' WHERE id = ?", temporaryGuest);
    paid(temporaryGuest, null, stay, null, "APPROVED", "150.00");

    assertThat(balance("2026-10"))
        .isEqualTo(
            "{\"month\":\"2026-10\",\"totalIncome\":890.00,\"incomeFromPayments\":850.00,"
                + "\"incomeFromFines\":40.00,\"totalExpenses\":0.00,\"net\":890.00}");
  }

  @Test
  void aMonthWithoutMovementsAnswersZerosWithTwoDecimals() throws Exception {
    insertExpense("10.00", "2026-10");

    String zeros =
        "\"totalIncome\":0.00,\"incomeFromPayments\":0.00,"
            + "\"incomeFromFines\":0.00,\"totalExpenses\":0.00,\"net\":0.00}";
    assertThat(balance("2026-08")).isEqualTo("{\"month\":\"2026-08\"," + zeros);
    assertThat(balance("0001-01")).isEqualTo("{\"month\":\"0001-01\"," + zeros);
    assertThat(balance("9999-12")).isEqualTo("{\"month\":\"9999-12\"," + zeros);
  }

  @Test
  void theCentsAddUpExactly() throws Exception {
    paid(contractGuest, contract, null, "2026-10", "APPROVED", "0.10");
    paid(temporaryGuest, null, stay, null, "APPROVED", "0.20");
    insertFine(contractGuest, "0.10", "2026-10");
    insertFine(temporaryGuest, "0.20", "2026-10");
    insertExpense("0.10", "2026-10");
    insertExpense("0.20", "2026-10");
    insertExpense("0.40", "2026-10");

    assertThat(balance("2026-10"))
        .isEqualTo(
            "{\"month\":\"2026-10\",\"totalIncome\":0.60,\"incomeFromPayments\":0.30,"
                + "\"incomeFromFines\":0.30,\"totalExpenses\":0.70,\"net\":-0.10}");
  }

  @Test
  void aMonthThatIsMissingOrNotYearMonthIsAValidationErrorOfTheMonth() throws Exception {
    mockMvc
        .perform(get(FINANCE).cookie(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.field").value("month"));
    for (String month : new String[] {"2026-13", "0000-01", "2026-1", "2026-00", "26-10", ""}) {
      mockMvc
          .perform(get(FINANCE).param("month", month).cookie(admin()))
          .andExpect(status().isBadRequest())
          .andExpect(satisfiesTheContractToAnInvalidRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
          .andExpect(jsonPath("$.field").value("month"));
    }
  }

  @Test
  void aGuestAndAnAnonymousRequestDoNotReachIt() throws Exception {
    mockMvc
        .perform(get(FINANCE).param("month", "2026-10").cookie(guest()))
        .andExpect(status().isForbidden())
        .andExpect(satisfiesTheContract())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    mockMvc
        .perform(get(FINANCE).param("month", "2026-10"))
        .andExpect(status().isUnauthorized())
        .andExpect(satisfiesTheContractToAnInvalidRequest())
        .andExpect(jsonPath("$.code").value("AUTH_NO_SESSION"));
  }

  // ------------------------------------------------------------------ helpers

  private String balance(String month) throws Exception {
    return mockMvc
        .perform(get(FINANCE).param("month", month).cookie(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(satisfiesTheContract())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** A payment with that amount, uploaded and resolved in October. */
  private Long paid(
      Long guest,
      @Nullable Long contractId,
      @Nullable Long stayId,
      @Nullable String period,
      String status,
      String amount) {
    Long id = insertPayment(guest, contractId, stayId, period, status, "2026-10-02T15:00:00Z");
    jdbc.update("UPDATE payments SET amount = ?::numeric WHERE id = ?", amount, id);
    return id;
  }

  private Long insertEnvironment(String code) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO environments (id, code, type, status) VALUES (?, ?, 'ROOM', 'ACTIVE')",
        id,
        code);
    return id;
  }

  private Long insertStay(Long guest, String start, String end) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, ?::date, ?::date, 300)",
        id,
        guest,
        start,
        end);
    return id;
  }

  private void insertFine(Long guest, String amount, String month) {
    jdbc.update(
        "INSERT INTO fines (id, guest_id, amount, month) VALUES (?, ?, ?::numeric, ?::date)",
        TestIds.next(),
        guest,
        amount,
        month + "-01");
  }

  private void insertExpense(String amount, String month) {
    jdbc.update(
        "INSERT INTO expenses (id, type, amount, month) VALUES (?, 'UTILITIES', ?::numeric,"
            + " ?::date)",
        TestIds.next(),
        amount,
        month + "-01");
  }
}
