package com.tarimatwasi.quipu.bff.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase.OwnGuestView;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** TAR-137: the area of the guard is the parsed path; however the raw URI is encoded. */
class GuestAreaFilterTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void theTemporaryAreaRefusesAGuestWithAContractWhateverTheUriSays() throws Exception {
    GuestAccountUseCase accounts = mock(GuestAccountUseCase.class);
    OwnGuestUseCase guests = mock(OwnGuestUseCase.class);
    when(accounts.guestOf(1L)).thenReturn(Optional.of(7L));
    when(guests.own(7L)).thenReturn(new OwnGuestView(7L, GuestKind.CONTRACT, GuestState.ACTIVE));
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null));
    var request = new MockHttpServletRequest("GET", "/bff/temporary%2Dguest/payment-status");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();

    new GuestAreaFilter(accounts, guests).doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"code\":\"AUTH_FORBIDDEN\"");
    assertThat(chain.getRequest()).isNull();
  }
}
