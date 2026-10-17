package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * BE-SPR-SEC-02 (TAR-170): the admin use cases check the role themselves, so a call that skips the
 * {@code /bff/admin/**} URL rule is refused too.
 */
@SpringBootTest(properties = "app.cors.allowed-origin=http://localhost:3000")
@ImportTestcontainers(PostgresContainers.class)
class MethodSecurityContractTest {

  @Autowired ManageGuestsUseCase guests;
  @Autowired ManageEnvironmentsUseCase environments;
  @Autowired ManageExpensesUseCase expenses;
  @Autowired OwnPaymentsUseCase ownPayments;
  @Autowired OwnGuestUseCase ownGuests;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    TestTables.clear(jdbc);
  }

  @Test
  @WithMockUser(roles = "GUEST")
  void guestUseCases_withoutAdminRole_areForbidden() {
    assertThatThrownBy(
            () -> guests.list(ManageGuestsUseCase.StatusFilter.ALL, null, PageQuery.first()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "GUEST")
  void environmentUseCases_withoutAdminRole_areForbidden() {
    assertThatThrownBy(
            () -> environments.list(ManageEnvironmentsUseCase.StatusFilter.ALL, PageQuery.first()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "GUEST")
  void expenseUseCases_withoutAdminRole_areForbidden() {
    assertThatThrownBy(() -> expenses.list(YearMonth.of(2026, 10), PageQuery.first()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ownGuestUseCases_withoutGuestRole_areForbidden() {
    assertThatThrownBy(() -> ownPayments.accountStatus(1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ownPayments.stayStatus(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ownGuests.own(1L)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminUseCases_withAdminRole_areAllowed() {
    assertThat(guests.list(ManageGuestsUseCase.StatusFilter.ALL, null, PageQuery.first()).content())
        .isEmpty();
    assertThat(
            environments
                .list(ManageEnvironmentsUseCase.StatusFilter.ALL, PageQuery.first())
                .content())
        .isEmpty();
    assertThat(expenses.list(YearMonth.of(2026, 10), PageQuery.first()).content()).isEmpty();
  }
}
