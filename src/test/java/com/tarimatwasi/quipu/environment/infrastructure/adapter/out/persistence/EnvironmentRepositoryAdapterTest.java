package com.tarimatwasi.quipu.environment.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.environment.domain.Environment;
import com.tarimatwasi.quipu.environment.domain.EnvironmentStatus;
import com.tarimatwasi.quipu.environment.domain.EnvironmentType;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.InvalidCursorException;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** BE-SPR-WEB-08 (TAR-176): the environments are read by last index, in the order of the code. */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class EnvironmentRepositoryAdapterTest {

  @Autowired EnvironmentRepositoryAdapter adapter;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transaction;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
    for (String code : new String[] {"203", "101", "102", "201", "202"}) {
      adapter.insert(code, EnvironmentType.ROOM);
    }
  }

  @Test
  void findAll_walksTheWholeListByCodeWithoutRepeatingOrSkipping() {
    PageQuery query = new PageQuery(null, 2);
    var codes = new java.util.ArrayList<String>();
    int pages = 0;
    do {
      CursorPage<Environment> page = adapter.findAll(null, query);
      page.content().forEach(environment -> codes.add(environment.code()));
      query = new PageQuery(page.nextCursor(), 2);
      pages++;
    } while (query.after() != null);

    assertThat(codes).containsExactly("101", "102", "201", "202", "203");
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void findAll_lastPageHasNoNextCursor() {
    CursorPage<Environment> page = adapter.findAll(null, new PageQuery(null, 5));

    assertThat(page.content()).hasSize(5);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void findAll_filtersByStatusAcrossPages() {
    CursorPage<Environment> first = adapter.findAll(null, new PageQuery(null, 1));
    // As in production, where the application service opens the transaction (BE-SPR-APP-02).
    transaction.executeWithoutResult(
        status -> adapter.updateStatus(first.content().get(0).id(), EnvironmentStatus.INACTIVE));

    CursorPage<Environment> active =
        adapter.findAll(EnvironmentStatus.ACTIVE, new PageQuery(null, 10));
    CursorPage<Environment> inactive =
        adapter.findAll(EnvironmentStatus.INACTIVE, new PageQuery(null, 10));

    assertThat(active.content()).extracting(Environment::code).doesNotContain("101").hasSize(4);
    assertThat(inactive.content()).extracting(Environment::code).containsExactly("101");
  }

  @Test
  void findAll_withAnInvalidCursor_isRefused() {
    assertThatThrownBy(() -> adapter.findAll(null, new PageQuery("garbage!", 5)))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void findAllByIds_readsOnlyTheEnvironmentsAsked() {
    var one = adapter.insert("A1", com.tarimatwasi.quipu.environment.domain.EnvironmentType.ROOM);
    adapter.insert("A2", com.tarimatwasi.quipu.environment.domain.EnvironmentType.CABIN);

    assertThat(adapter.findAllByIds(java.util.List.of(one.id(), -3L)))
        .extracting(e -> e.code())
        .containsExactly("A1");
  }
}
