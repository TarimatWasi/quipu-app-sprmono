package com.tarimatwasi.quipu.guest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageStaysUseCase.StayView;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase.OwnGuestView;
import com.tarimatwasi.quipu.guest.application.port.out.GuestRepositoryPort;
import com.tarimatwasi.quipu.guest.application.port.out.StayRepositoryPort;
import com.tarimatwasi.quipu.guest.domain.DocumentType;
import com.tarimatwasi.quipu.guest.domain.Guest;
import com.tarimatwasi.quipu.guest.domain.GuestStatus;
import com.tarimatwasi.quipu.guest.domain.GuestType;
import com.tarimatwasi.quipu.guest.domain.Stay;
import com.tarimatwasi.quipu.support.TestIds;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnGuestServiceTest {

  private static final Long GUEST_ID = TestIds.next();

  @Mock GuestRepositoryPort guests;
  @Mock StayRepositoryPort stays;

  private OwnGuestService service() {
    return new OwnGuestService(guests, stays);
  }

  private static Stay stay(long id, String start, String end) {
    return new Stay(
        id, GUEST_ID, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal("150"));
  }

  @Test
  void own_tellsTheTypeAndTheStateOfTheGuest() {
    when(guests.findById(GUEST_ID))
        .thenReturn(
            Optional.of(
                new Guest(
                    GUEST_ID,
                    DocumentType.DNI,
                    "12345678",
                    null,
                    null,
                    null,
                    GuestType.TEMPORARY,
                    GuestStatus.ONBOARDING,
                    null,
                    null,
                    null,
                    null)));

    assertThat(service().own(GUEST_ID))
        .isEqualTo(new OwnGuestView(GUEST_ID, GuestKind.TEMPORARY, GuestState.ONBOARDING));
  }

  @Test
  void own_aGuestThatIsNotThereIsNotFound() {
    when(guests.findById(GUEST_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().own(GUEST_ID)).isInstanceOf(GuestNotFoundException.class);
  }

  @Test
  void stays_andTheRelevantOneAreReadFromEveryStayOfTheGuest() {
    when(stays.findAllByGuest(GUEST_ID))
        .thenReturn(
            List.of(stay(1, "2026-09-01", "2026-09-05"), stay(2, "2026-11-01", "2026-11-05")));

    assertThat(service().stays(GUEST_ID)).extracting(StayView::id).containsExactly(1L, 2L);
    assertThat(service().relevantStay(GUEST_ID, LocalDate.parse("2026-10-14")))
        .map(StayView::id)
        .contains(2L);
  }
}
