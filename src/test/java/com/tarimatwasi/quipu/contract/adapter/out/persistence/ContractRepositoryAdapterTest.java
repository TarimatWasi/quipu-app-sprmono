package com.tarimatwasi.quipu.contract.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.contract.domain.Contract;
import com.tarimatwasi.quipu.contract.domain.ContractStatus;
import com.tarimatwasi.quipu.contract.port.out.ContractRepositoryPort.Draft;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.CursorToken;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestIds;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-40: the contracts, their derived state and the listing read by last index. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class ContractRepositoryAdapterTest {

  private static final LocalDate TODAY = LocalDate.parse("2026-10-14");

  @Autowired ContractRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;

  private Long guestId;
  private Long otherGuestId;
  private Long roomId;
  private Long otherRoomId;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    guestId = insertGuest("40000001");
    otherGuestId = insertGuest("40000002");
    roomId = insertEnvironment("101");
    otherRoomId = insertEnvironment("102");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Long insertGuest(String number) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'CONTRACT', 'ACTIVE')",
        id,
        number);
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

  private Contract insert(Long guest, Long environment, String start, @Nullable String end) {
    return adapter.insert(
        new Draft(
            environment,
            guest,
            LocalDate.parse(start),
            end == null ? null : LocalDate.parse(end),
            new BigDecimal("800.50"),
            5,
            3,
            "contracts/" + TestIds.next() + ".pdf",
            "contrato.pdf",
            1234));
  }

  @Test
  void insert_storesTheContractActiveWithAnId() {
    Contract contract = insert(guestId, roomId, "2026-10-01", null);

    assertThat(contract.id()).isNotNull();
    assertThat(contract.guestId()).isEqualTo(guestId);
    assertThat(contract.environmentId()).isEqualTo(roomId);
    assertThat(contract.endDate()).isNull();
    assertThat(contract.monthlyAmount()).isEqualByComparingTo("800.50");
    assertThat(contract.paymentDay()).isEqualTo(5);
    assertThat(contract.graceDays()).isEqualTo(3);
    assertThat(contract.sizeBytes()).isEqualTo(1234);
    assertThat(contract.terminated()).isFalse();
    assertThat(adapter.findById(contract.id())).contains(contract);
  }

  @Test
  void findById_ofAnUnknownContractIsEmpty() {
    assertThat(adapter.findById(-1L)).isEmpty();
  }

  @Test
  void theSchemaRefusesAnEndDateThatIsNotAfterTheStartAndAnUnknownEnvironment() {
    assertThatThrownBy(() -> insert(guestId, roomId, "2026-10-01", "2026-10-01"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(guestId, -5L, "2026-10-01", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_storesTheTermination() {
    Contract contract = insert(guestId, roomId, "2026-10-01", null);

    Contract saved = adapter.save(contract.terminate());

    assertThat(saved.terminated()).isTrue();
    assertThat(adapter.findById(contract.id()).orElseThrow().terminated()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM contracts WHERE id = ?", String.class, contract.id()))
        .isEqualTo("TERMINATED");
  }

  @Test
  void existsInForce_countsOpenEndedFutureAndEndingTodayAndNotTheRest() {
    assertThat(adapter.existsInForce(roomId, TODAY)).isFalse();

    insert(guestId, roomId, "2026-01-01", "2026-10-13");
    assertThat(adapter.existsInForce(roomId, TODAY)).as("expired yesterday").isFalse();

    Contract endingToday = insert(guestId, roomId, "2026-01-01", "2026-10-14");
    assertThat(adapter.existsInForce(roomId, TODAY)).as("ends today").isTrue();
    adapter.save(endingToday.terminate());
    assertThat(adapter.existsInForce(roomId, TODAY)).as("terminated").isFalse();

    insert(otherGuestId, roomId, "2026-12-01", "2027-12-01");
    assertThat(adapter.existsInForce(roomId, TODAY)).as("starts in the future").isTrue();
    assertThat(adapter.existsInForce(otherRoomId, TODAY)).as("another environment").isFalse();

    Contract openEnded = insert(guestId, otherRoomId, "2026-01-01", null);
    assertThat(adapter.existsInForce(otherRoomId, TODAY)).as("open-ended").isTrue();
    assertThat(openEnded.id()).isNotNull();
  }

  @Test
  void anExpiredContractFreesTheEnvironmentWithoutAnyJob() {
    insert(guestId, roomId, "2026-09-01", "2026-10-14");

    assertThat(adapter.existsInForce(roomId, TODAY)).isTrue();
    assertThat(adapter.existsInForce(roomId, TODAY.plusDays(1))).isFalse();
  }

  @Test
  void findLatestByGuest_isTheLastLoadedWhateverItsState() {
    assertThat(adapter.findLatestByGuest(guestId)).isEmpty();
    Contract first = insert(guestId, roomId, "2026-01-01", "2026-02-01");
    Contract second = insert(guestId, otherRoomId, "2026-03-01", null);
    insert(otherGuestId, roomId, "2026-04-01", null);
    adapter.save(second.terminate());

    assertThat(adapter.findLatestByGuest(guestId).orElseThrow().id()).isEqualTo(second.id());
    assertThat(first.id()).isNotEqualTo(second.id());
  }

  @Test
  void findAll_filtersByTheStateOfTheDay() {
    Contract active = insert(guestId, roomId, "2026-01-01", null);
    Contract expired = insert(otherGuestId, otherRoomId, "2026-01-01", "2026-09-01");
    Contract terminated = insert(guestId, insertEnvironment("103"), "2026-01-01", null);
    adapter.save(terminated.terminate());
    // a terminated one whose end date has also passed is TERMINATED, not EXPIRED
    Contract terminatedAndPast =
        insert(guestId, insertEnvironment("104"), "2026-01-01", "2026-02-01");
    adapter.save(terminatedAndPast.terminate());

    assertThat(ids(adapter.findAll(ContractStatus.ACTIVE, TODAY, PageQuery.first())))
        .containsExactly(active.id());
    assertThat(ids(adapter.findAll(ContractStatus.EXPIRED, TODAY, PageQuery.first())))
        .containsExactly(expired.id());
    assertThat(ids(adapter.findAll(ContractStatus.TERMINATED, TODAY, PageQuery.first())))
        .containsExactlyInAnyOrder(terminated.id(), terminatedAndPast.id());
    assertThat(adapter.findAll(null, TODAY, PageQuery.first()).content()).hasSize(4);
    // the same row changes state with the day, with no job in between
    Contract endsInDecember =
        insert(otherGuestId, insertEnvironment("105"), "2026-01-01", "2026-12-01");
    assertThat(ids(adapter.findAll(ContractStatus.ACTIVE, TODAY, PageQuery.first())))
        .contains(endsInDecember.id());
    assertThat(
            ids(
                adapter.findAll(
                    ContractStatus.EXPIRED, LocalDate.parse("2027-01-01"), PageQuery.first())))
        .contains(endsInDecember.id())
        .doesNotContain(active.id());
  }

  @Test
  void findAll_walksNewestFirstWithoutRepeatingOrSkipping() {
    List<Long> created = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      created.add(insert(guestId, insertEnvironment("20" + i), "2026-01-01", null).id());
    }
    List<Long> seen = new ArrayList<>();
    String cursor = null;
    do {
      CursorPage<Contract> page = adapter.findAll(null, TODAY, new PageQuery(cursor, 2));
      page.content().forEach(contract -> seen.add(contract.id()));
      cursor = page.nextCursor();
    } while (cursor != null);

    List<Long> newestFirst = new ArrayList<>(created);
    java.util.Collections.reverse(newestFirst);
    assertThat(seen).containsExactlyElementsOf(newestFirst);
  }

  @Test
  void findAll_refusesACursorThatIsNotOurs() {
    assertThatThrownBy(() -> adapter.findAll(null, TODAY, new PageQuery("nope", 5)))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(
            () ->
                adapter.findAll(
                    null, TODAY, new PageQuery(CursorToken.encode("not-a-date", "1"), 5)))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(
            () ->
                adapter.findAll(
                    null,
                    TODAY,
                    new PageQuery(CursorToken.encode("+12345-01-01T00:00:00", "1"), 5)))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(
            () ->
                adapter.findAll(
                    null, TODAY, new PageQuery(CursorToken.encode("2026-01-01T00:00:00", "x"), 5)))
        .isInstanceOf(InvalidCursorException.class);
  }

  private static List<Long> ids(CursorPage<Contract> page) {
    return page.content().stream().map(Contract::id).toList();
  }
}
