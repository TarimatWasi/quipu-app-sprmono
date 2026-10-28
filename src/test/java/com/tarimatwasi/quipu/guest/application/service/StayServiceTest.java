package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.EmptyGuestUpdateException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotActiveException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotTemporaryException;
import com.tarimatwasi.quipu.guest.application.port.in.InvalidGuestStayException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.NewStay;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayChange;
import com.tarimatwasi.quipu.guest.application.port.in.StayNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.StayOverlapException;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.shared.paging.CursorPage;
import com.tarimatwasi.quipu.shared.paging.PageQuery;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StayServiceTest {

  private static final Long GUEST_ID = TestIds.next();
  private static final Long STAY_ID = TestIds.next();

  @Mock GuestRepositoryPort guests;
  @Mock StayRepositoryPort stays;

  private StayService service() {
    return new StayService(guests, stays);
  }

  private static Guest guest(GuestType type, GuestStatus status) {
    return new Guest(
        GUEST_ID,
        DocumentType.DNI,
        "12345678",
        null,
        null,
        null,
        type,
        status,
        null,
        null,
        null,
        null);
  }

  private static LocalDate day(String date) {
    return LocalDate.parse(date);
  }

  private static NewStay newStay(String start, String end, BigDecimal amount) {
    return new NewStay(day(start), day(end), amount);
  }

  private static Stay stay(Long id, String start, String end, String amount) {
    return new Stay(id, GUEST_ID, day(start), day(end), new BigDecimal(amount));
  }

  private void activeTemporaryGuest() {
    when(guests.findByIdForUpdate(GUEST_ID))
        .thenReturn(Optional.of(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE)));
  }

  // --- list ---

  @Test
  void list_ofAnUnknownGuestIsNotFound() {
    when(guests.findById(GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().list(GUEST_ID, PageQuery.first()))
        .isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void list_keepsTheCursorOfTheNextPage() {
    PageQuery page = new PageQuery(null, 1);
    when(guests.findById(GUEST_ID))
        .thenReturn(Optional.of(guest(GuestType.TEMPORARY, GuestStatus.ACTIVE)));
    when(stays.findByGuest(GUEST_ID, page))
        .thenReturn(
            new CursorPage<>(List.of(stay(STAY_ID, "2026-10-10", "2026-10-20", "150.50")), "next"));

    var result = service().list(GUEST_ID, page);

    assertThat(result.nextCursor()).isEqualTo("next");
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).id()).isEqualTo(STAY_ID);
    assertThat(result.content().get(0).agreedAmount()).isEqualByComparingTo("150.50");
  }

  // --- create ---

  @Test
  void create_storesTheStayOfAnActiveTemporaryGuest() {
    activeTemporaryGuest();
    when(stays.findAllByGuest(GUEST_ID))
        .thenReturn(List.of(stay(TestIds.next(), "2026-09-01", "2026-09-10", "100")));
    when(stays.insert(GUEST_ID, day("2026-10-10"), day("2026-10-20"), new BigDecimal("300.50")))
        .thenReturn(stay(STAY_ID, "2026-10-10", "2026-10-20", "300.50"));

    var created =
        service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", new BigDecimal("300.50")));

    assertThat(created.id()).isEqualTo(STAY_ID);
    assertThat(created.endDate()).isEqualTo(day("2026-10-20"));
  }

  @Test
  void create_allowsAFutureStayStartingTheDayAfterTheRunningOneEnds() {
    activeTemporaryGuest();
    when(stays.findAllByGuest(GUEST_ID))
        .thenReturn(List.of(stay(TestIds.next(), "2026-10-01", "2026-10-20", "100")));
    when(stays.insert(GUEST_ID, day("2026-10-21"), day("2026-10-21"), BigDecimal.TEN))
        .thenReturn(stay(STAY_ID, "2026-10-21", "2026-10-21", "10"));

    assertThat(service().create(GUEST_ID, newStay("2026-10-21", "2026-10-21", BigDecimal.TEN)))
        .isNotNull();
  }

  @Test
  void create_ofAnUnknownGuestIsNotFound() {
    when(guests.findByIdForUpdate(GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", BigDecimal.TEN)))
        .isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void create_isOnlyForTemporaryGuests() {
    when(guests.findByIdForUpdate(GUEST_ID))
        .thenReturn(Optional.of(guest(GuestType.CONTRACT, GuestStatus.ACTIVE)));

    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", BigDecimal.TEN)))
        .isInstanceOf(GuestNotTemporaryException.class);
    verify(stays, never()).insert(any(), any(), any(), any());
  }

  @Test
  void create_isOnlyForActiveGuests() {
    for (var status :
        List.of(GuestStatus.PENDING_ACTIVATION, GuestStatus.ONBOARDING, GuestStatus.INACTIVE)) {
      when(guests.findByIdForUpdate(GUEST_ID))
          .thenReturn(Optional.of(guest(GuestType.TEMPORARY, status)));

      assertThatThrownBy(
              () -> service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", BigDecimal.TEN)))
          .as(status.name())
          .isInstanceOfSatisfying(
              GuestNotActiveException.class, e -> assertThat(e.guestId()).isEqualTo(GUEST_ID));
    }
    verify(stays, never()).insert(any(), any(), any(), any());
  }

  @Test
  void create_refusesDatesThatCrossAnotherStayOfTheGuest() {
    activeTemporaryGuest();
    when(stays.findAllByGuest(GUEST_ID))
        .thenReturn(List.of(stay(TestIds.next(), "2026-10-10", "2026-10-20", "100")));

    // Sharing only the last day already crosses: the dates are inclusive.
    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("2026-10-20", "2026-10-25", BigDecimal.TEN)))
        .isInstanceOf(StayOverlapException.class);
    verify(stays, never()).insert(any(), any(), any(), any());
  }

  @Test
  void create_validatesTheDatesAndTheAmountBeforeTouchingTheGuest() {
    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("2026-10-20", "2026-10-10", BigDecimal.TEN)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("endDate"));
    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("0001-01-01", "2026-10-10", BigDecimal.TEN)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("startDate"));
    assertThatThrownBy(
            () -> service().create(GUEST_ID, newStay("2026-10-10", "+10000-01-01", BigDecimal.TEN)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("endDate"));
    for (var bad :
        List.of(
            BigDecimal.ZERO,
            new BigDecimal("-1"),
            new BigDecimal("10.001"),
            new BigDecimal("100000000"))) {
      assertThatThrownBy(() -> service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", bad)))
          .as(bad.toPlainString())
          .isInstanceOfSatisfying(
              InvalidGuestStayException.class,
              e -> assertThat(e.field()).isEqualTo("agreedAmount"));
    }
    verify(guests, never()).findByIdForUpdate(any());
  }

  @Test
  void create_acceptsTheLimitAmount() {
    activeTemporaryGuest();
    BigDecimal limit = new BigDecimal("99999999.99");
    when(stays.findAllByGuest(GUEST_ID)).thenReturn(List.of());
    when(stays.insert(GUEST_ID, day("2026-10-10"), day("2026-10-20"), limit))
        .thenReturn(stay(STAY_ID, "2026-10-10", "2026-10-20", "99999999.99"));

    assertThat(service().create(GUEST_ID, newStay("2026-10-10", "2026-10-20", limit))).isNotNull();
  }

  // --- update ---

  @Test
  void update_changesOnlyThePresentFields() {
    activeTemporaryGuest();
    var current = stay(STAY_ID, "2026-10-10", "2026-10-20", "100");
    when(stays.findByIdAndGuest(STAY_ID, GUEST_ID)).thenReturn(Optional.of(current));
    when(stays.findAllByGuest(GUEST_ID)).thenReturn(List.of(current));
    when(stays.update(any())).thenAnswer(call -> call.getArgument(0));

    var updated =
        service().update(GUEST_ID, STAY_ID, new StayChange(null, day("2026-10-25"), null));

    assertThat(updated.startDate()).isEqualTo(day("2026-10-10"));
    assertThat(updated.endDate()).isEqualTo(day("2026-10-25"));
    assertThat(updated.agreedAmount()).isEqualByComparingTo("100");
  }

  @Test
  void update_doesNotCountTheStayItselfAsAnOverlapButCountsTheOthers() {
    activeTemporaryGuest();
    var current = stay(STAY_ID, "2026-10-10", "2026-10-20", "100");
    var other = stay(TestIds.next(), "2026-11-01", "2026-11-10", "100");
    when(stays.findByIdAndGuest(STAY_ID, GUEST_ID)).thenReturn(Optional.of(current));
    when(stays.findAllByGuest(GUEST_ID)).thenReturn(List.of(current, other));

    assertThatThrownBy(
            () ->
                service().update(GUEST_ID, STAY_ID, new StayChange(null, day("2026-11-01"), null)))
        .isInstanceOf(StayOverlapException.class);
    verify(stays, never()).update(any());
  }

  @Test
  void update_ofAStayOfAnotherGuestOrAnUnknownOneIsNotFound() {
    activeTemporaryGuest();
    when(stays.findByIdAndGuest(STAY_ID, GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().update(GUEST_ID, STAY_ID, new StayChange(null, null, BigDecimal.ONE)))
        .isInstanceOf(StayNotFoundException.class);
  }

  @Test
  void update_ofAnUnknownGuestIsNotFound() {
    when(guests.findByIdForUpdate(GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service().update(GUEST_ID, STAY_ID, new StayChange(null, null, BigDecimal.ONE)))
        .isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void update_needsAtLeastOneField() {
    assertThatThrownBy(() -> service().update(GUEST_ID, STAY_ID, new StayChange(null, null, null)))
        .isInstanceOf(EmptyGuestUpdateException.class);
  }

  @Test
  void update_validatesTheResultingDatesAndAmount() {
    activeTemporaryGuest();
    var current = stay(STAY_ID, "2026-10-10", "2026-10-20", "100");
    when(stays.findByIdAndGuest(STAY_ID, GUEST_ID)).thenReturn(Optional.of(current));

    assertThatThrownBy(
            () ->
                service().update(GUEST_ID, STAY_ID, new StayChange(day("2026-10-21"), null, null)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("endDate"));
    assertThatThrownBy(
            () -> service().update(GUEST_ID, STAY_ID, new StayChange(null, null, BigDecimal.ZERO)))
        .isInstanceOfSatisfying(
            InvalidGuestStayException.class, e -> assertThat(e.field()).isEqualTo("agreedAmount"));
    verify(stays, never()).update(any());
  }

  @Test
  void get_returnsTheStayOfTheGuestAndNotFoundForAnyOtherCombination() {
    var stay =
        new Stay(
            STAY_ID,
            GUEST_ID,
            LocalDate.parse("2026-10-10"),
            LocalDate.parse("2026-10-20"),
            new BigDecimal("150.50"));
    when(stays.findByIdAndGuest(STAY_ID, GUEST_ID)).thenReturn(Optional.of(stay));
    when(stays.findByIdAndGuest(STAY_ID, 5L)).thenReturn(Optional.empty());

    assertThat(service().get(GUEST_ID, STAY_ID).endDate()).isEqualTo(LocalDate.parse("2026-10-20"));
    assertThatThrownBy(() -> service().get(5L, STAY_ID)).isInstanceOf(StayNotFoundException.class);
  }

  /** RF-18: the stays that start in the month, from its first to its last day, of any guest. */
  @Test
  void theStaysThatStartInAMonthAreReadFromItsFirstToItsLastDay() {
    when(stays.findIdsStartingBetween(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
        .thenReturn(List.of(4L, 8L));

    assertThat(service().idsStartingIn(YearMonth.of(2026, 2))).containsExactly(4L, 8L);
  }

  @Test
  void theStaysInCourseAreOnePerGuestTheLowestIdKept() {
    LocalDate today = day("2026-10-14");
    Long other = TestIds.next();
    when(stays.findAllInForceOn(today))
        .thenReturn(
            List.of(
                new Stay(3L, GUEST_ID, day("2026-10-01"), day("2026-10-20"), BigDecimal.TEN),
                new Stay(5L, other, day("2026-10-14"), day("2026-10-14"), BigDecimal.ONE),
                new Stay(9L, GUEST_ID, day("2026-10-10"), day("2026-10-30"), BigDecimal.TEN)));

    assertThat(service().inForceOn(today))
        .extracting(ManageStaysUseCase.StayView::id)
        .containsExactly(3L, 5L);
  }
}
