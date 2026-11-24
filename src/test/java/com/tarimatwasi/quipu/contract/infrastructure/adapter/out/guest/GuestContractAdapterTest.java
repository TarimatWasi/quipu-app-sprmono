package com.tarimatwasi.quipu.contract.infrastructure.adapter.out.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.contract.application.port.out.ContractGuestPort.GuestProfile;
import com.tarimatwasi.quipu.guest.application.port.in.GuestLabelsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.application.port.in.LockGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestSummaryView;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.IdDocumentKind;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuestContractAdapterTest {

  private final ManageGuestsUseCase guests = mock(ManageGuestsUseCase.class);
  private final GuestContractAdapter adapter =
      new GuestContractAdapter(
          guests, mock(LockGuestUseCase.class), mock(GuestLabelsUseCase.class));

  /** The profile is read from the summary, never from the detail of the screen of the guest. */
  @Test
  void theProfileComesFromTheSummaryOfTheGuest() {
    when(guests.summariesOf(Set.of(5L)))
        .thenReturn(
            Map.of(
                5L,
                new GuestSummaryView(
                    5L,
                    IdDocumentKind.DNI,
                    "12345678",
                    "Ana",
                    GuestKind.CONTRACT,
                    GuestState.ACTIVE,
                    true)));

    assertThat(adapter.profileOf(5L)).isEqualTo(new GuestProfile(true, true));
  }

  @Test
  void aGuestThatIsGoneIsNotFound() {
    when(guests.summariesOf(Set.of(5L))).thenReturn(Map.of());

    assertThatThrownBy(() -> adapter.profileOf(5L)).isInstanceOf(GuestNotFoundException.class);
  }
}
