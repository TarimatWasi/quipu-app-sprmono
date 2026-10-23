package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase.OwnGuestView;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** TAR-137: the area of a guard is fixed; the raw URI, however encoded, does not change it. */
class GuestAreaInterceptorTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theTemporaryAreaRefusesAGuestWithAContractWhateverTheUriSays() {
    GuestAccountUseCase accounts = mock(GuestAccountUseCase.class);
    OwnGuestUseCase guests = mock(OwnGuestUseCase.class);
    when(accounts.guestOf(1L)).thenReturn(Optional.of(7L));
    when(guests.own(7L)).thenReturn(new OwnGuestView(7L, GuestKind.CONTRACT, GuestState.ACTIVE));
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null));
    var request = new MockHttpServletRequest("GET", "/bff/temporary%2Dguest/payment-status");
    var guard = new GuestAreaInterceptor(GuestKind.TEMPORARY, accounts, guests);

    assertThatThrownBy(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(OtherGuestTypeException.class);
  }
}
