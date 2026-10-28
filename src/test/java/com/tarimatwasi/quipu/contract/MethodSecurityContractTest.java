package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase;
import com.tarimatwasi.quipu.auth.port.in.CreateAdministratorUseCase.CreateAdministratorCommand;
import com.tarimatwasi.quipu.auth.port.in.GuestAccessUseCase;
import com.tarimatwasi.quipu.auth.port.in.LoginUseCase.DocumentKind;
import com.tarimatwasi.quipu.auth.port.in.ManageAdministratorsUseCase;
import com.tarimatwasi.quipu.auth.port.in.ManageAdministratorsUseCase.StatusChangeCommand;
import com.tarimatwasi.quipu.environment.port.in.ManageEnvironmentsUseCase;
import com.tarimatwasi.quipu.expense.port.in.ManageExpensesUseCase;
import com.tarimatwasi.quipu.fine.port.in.ManageFinesUseCase;
import com.tarimatwasi.quipu.fine.port.in.ManageFinesUseCase.FineCommand;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.payment.port.in.FinanceSummaryUseCase;
import com.tarimatwasi.quipu.payment.port.in.ManagePaymentsUseCase;
import com.tarimatwasi.quipu.payment.port.in.OwnPaymentsUseCase;
import com.tarimatwasi.quipu.shared.access.AccessMode;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.PostgresContainers;
import com.tarimatwasi.quipu.support.TestTables;
import java.math.BigDecimal;
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
  @Autowired ManageFinesUseCase fines;
  @Autowired OwnPaymentsUseCase ownPayments;
  @Autowired ManagePaymentsUseCase payments;
  @Autowired FinanceSummaryUseCase finance;
  @Autowired ManageStaysUseCase stays;
  @Autowired OwnGuestUseCase ownGuests;
  @Autowired GuestAccessUseCase guestAccess;
  @Autowired CreateAdministratorUseCase administrators;
  @Autowired ManageAdministratorsUseCase administratorManagement;
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

  /** TAR-36: the accesses of a guest are handed only in the flows of the administrator. */
  @Test
  @WithMockUser(roles = "GUEST")
  void guestAccessUseCases_withoutAdminRole_areForbidden() {
    assertThatThrownBy(() -> guestAccess.issueAccess(1L, AccessMode.LINK))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> guestAccess.revokeAccess(1L))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** TAR-188: only an administrator creates another one (RF-22). */
  @Test
  @WithMockUser(roles = "GUEST")
  void createAdministrator_withoutAdminRole_isForbidden() {
    assertThatThrownBy(
            () ->
                administrators.create(
                    new CreateAdministratorCommand(
                        1L, DocumentKind.DNI, "22222222", "n@example.test", "Creador12345")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
  }

  /** TAR-127: only an administrator lists, deactivates or reactivates another one. */
  @Test
  @WithMockUser(roles = "GUEST")
  void manageAdministrators_withoutAdminRole_isForbidden() {
    jdbc.update(
        "INSERT INTO users (id, email, document_type, document_number, password_hash, role,"
            + " must_change_password, status) VALUES (2, 'a@example.test', 'DNI', '22222222',"
            + " 'hash', 'ADMIN', FALSE, 'ACTIVE')");
    assertThatThrownBy(() -> administratorManagement.list(1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> administratorManagement.deactivate(new StatusChangeCommand(1L, 2L, "Actor12345")))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> administratorManagement.reactivate(new StatusChangeCommand(1L, 2L, "Actor12345")))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = 2", String.class))
        .isEqualTo("ACTIVE");
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

  /** TAR-47: the fines are handled only by the administrator (RF-19). */
  @Test
  @WithMockUser(roles = "GUEST")
  void fineUseCases_withoutAdminRole_areForbidden() {
    assertThatThrownBy(() -> fines.list(YearMonth.of(2026, 10)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> fines.create(new FineCommand(1L, BigDecimal.TEN, YearMonth.of(2026, 10), null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> fines.delete(1L)).isInstanceOf(AccessDeniedException.class);
  }

  /** TAR-48: the balance of a month and the reads it is made of are only for the administrator. */
  @Test
  @WithMockUser(roles = "GUEST")
  void financeSummaryAndItsReads_withoutAdminRole_areForbidden() {
    YearMonth october = YearMonth.of(2026, 10);
    assertThatThrownBy(() -> finance.summary(october)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> fines.totalOf(october)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> expenses.totalOf(october)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> stays.idsStartingIn(october))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "GUEST")
  void paymentHistoryOfTheAdministrator_withoutAdminRole_isForbidden() {
    assertThatThrownBy(() -> payments.history(1L, PageQuery.first()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> payments.accountStatus(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> payments.environmentAccountStatus(1L))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void ownGuestUseCases_withoutGuestRole_areForbidden() {
    assertThatThrownBy(() -> ownPayments.accountStatus(1L))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ownPayments.stayStatus(1L)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> ownPayments.history(1L, PageQuery.first()))
        .isInstanceOf(AccessDeniedException.class);
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
    assertThat(fines.list(YearMonth.of(2026, 10))).isEmpty();
    assertThat(finance.summary(YearMonth.of(2026, 10)).net()).hasToString("0.00");
    assertThat(administratorManagement.list(1L)).isEmpty();
  }
}
