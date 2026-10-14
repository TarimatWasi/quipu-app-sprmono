package com.tarimatwasi.quipu.guest.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.guest.domain.Stay;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** TAR-182: the stays of a guest, read by last index and newest first. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class StayRepositoryAdapterTest {

  @Autowired StayRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;

  private Long guestId;
  private Long otherGuestId;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    guestId = insertGuest("30000001");
    otherGuestId = insertGuest("30000002");
  }

  @AfterEach
  void tearDown() {
    TestTables.clear(jdbc);
  }

  private Long insertGuest(String number) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO guests (id, document_type, document_number, guest_type, status)"
            + " VALUES (?, 'DNI', ?, 'TEMPORARY', 'ACTIVE')",
        id,
        number);
    return id;
  }

  private Stay insert(Long guest, String start, String end, String amount) {
    return adapter.insert(
        guest, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(amount));
  }

  @Test
  void insert_storesTheStayAndGivesItAnId() {
    Stay stay = insert(guestId, "2026-10-10", "2026-10-20", "150.50");

    assertThat(stay.id()).isNotNull();
    assertThat(stay.guestId()).isEqualTo(guestId);
    assertThat(stay.startDate()).isEqualTo(LocalDate.parse("2026-10-10"));
    assertThat(stay.agreedAmount()).isEqualByComparingTo("150.50");
    assertThat(adapter.findByIdAndGuest(stay.id(), guestId)).contains(stay);
  }

  @Test
  void findByIdAndGuest_doesNotShowTheStayOfAnotherGuest() {
    Stay stay = insert(guestId, "2026-10-10", "2026-10-20", "100");

    assertThat(adapter.findByIdAndGuest(stay.id(), otherGuestId)).isEmpty();
  }

  @Test
  void findByGuest_walksTheStaysNewestFirstWithoutRepeatingOrSkipping() {
    insert(guestId, "2026-01-01", "2026-01-10", "100");
    insert(guestId, "2026-09-01", "2026-09-10", "100");
    insert(guestId, "2026-05-01", "2026-05-10", "100");
    insert(guestId, "2026-12-01", "2026-12-10", "100");
    insert(otherGuestId, "2026-11-01", "2026-11-10", "100");

    List<LocalDate> starts = new ArrayList<>();
    PageQuery query = new PageQuery(null, 2);
    int pages = 0;
    do {
      CursorPage<Stay> page = adapter.findByGuest(guestId, query);
      page.content().forEach(stay -> starts.add(stay.startDate()));
      query = new PageQuery(page.nextCursor(), 2);
      pages++;
    } while (query.after() != null);

    assertThat(starts)
        .containsExactly(
            LocalDate.parse("2026-12-01"),
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-05-01"),
            LocalDate.parse("2026-01-01"));
    assertThat(pages).isEqualTo(2);
  }

  @Test
  void findByGuest_breaksATieOfTheStartDateById() {
    // Two stays of one guest cannot overlap, but a tie in the sort key must still be stable.
    Long first = jdbcStay(guestId, "2026-10-10", "2026-10-10");
    Long second = jdbcStay(guestId, "2026-10-10", "2026-10-10");

    CursorPage<Stay> firstPage = adapter.findByGuest(guestId, new PageQuery(null, 1));
    CursorPage<Stay> secondPage =
        adapter.findByGuest(guestId, new PageQuery(firstPage.nextCursor(), 1));

    Long newer = Math.max(first, second);
    Long older = Math.min(first, second);
    assertThat(firstPage.content().get(0).id()).isEqualTo(newer);
    assertThat(secondPage.content().get(0).id()).isEqualTo(older);
    assertThat(secondPage.nextCursor()).isNull();
  }

  private Long jdbcStay(Long guest, String start, String end) {
    Long id = TestIds.next();
    jdbc.update(
        "INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount)"
            + " VALUES (?, ?, ?::date, ?::date, 10)",
        id,
        guest,
        start,
        end);
    return id;
  }

  @Test
  void findByGuest_refusesACursorThatIsNotAKey() {
    for (String bad :
        List.of(
            "not-a-cursor",
            CursorToken.encode("not-a-date", "1"),
            CursorToken.encode("2026-10-10", "x"),
            CursorToken.encode("+10000-01-01", "1"),
            CursorToken.encode("0001-01-01", "1"))) {
      assertThatThrownBy(() -> adapter.findByGuest(guestId, new PageQuery(bad, 5)))
          .as(bad)
          .isInstanceOf(InvalidCursorException.class);
    }
  }

  @Test
  void findAllByGuest_returnsOnlyTheStaysOfThatGuest() {
    insert(guestId, "2026-01-01", "2026-01-10", "100");
    insert(guestId, "2026-09-01", "2026-09-10", "100");
    insert(otherGuestId, "2026-11-01", "2026-11-10", "100");

    assertThat(adapter.findAllByGuest(guestId)).hasSize(2);
    assertThat(adapter.findAllByGuest(otherGuestId)).hasSize(1);
  }

  @Test
  void update_changesTheDatesAndTheAmountAndKeepsTheGuest() {
    Stay stay = insert(guestId, "2026-10-10", "2026-10-20", "100");

    Stay updated =
        adapter.update(
            new Stay(
                stay.id(),
                guestId,
                LocalDate.parse("2026-10-12"),
                LocalDate.parse("2026-10-25"),
                new BigDecimal("250.75")));

    assertThat(updated.startDate()).isEqualTo(LocalDate.parse("2026-10-12"));
    assertThat(updated.endDate()).isEqualTo(LocalDate.parse("2026-10-25"));
    assertThat(updated.agreedAmount()).isEqualByComparingTo("250.75");
    assertThat(updated.guestId()).isEqualTo(guestId);
  }

  @Test
  void update_ofAMissingStayFails() {
    Stay missing =
        new Stay(
            TestIds.next(),
            guestId,
            LocalDate.parse("2026-10-12"),
            LocalDate.parse("2026-10-25"),
            BigDecimal.TEN);

    assertThatThrownBy(() -> adapter.update(missing))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void theBaseRefusesWhatTheRulesRefuse() {
    assertThatThrownBy(() -> insert(guestId, "2026-10-20", "2026-10-10", "100"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_stays_dates");
    assertThatThrownBy(() -> insert(guestId, "2026-10-10", "2026-10-20", "0"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_stays_agreed_amount");
    assertThatThrownBy(() -> insert(TestIds.next(), "2026-10-10", "2026-10-20", "10"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_stays_guest");
  }
}
