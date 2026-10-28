package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tarimatwasi.quipu.auth.application.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.application.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.application.port.in.OwnGuestUseCase.OwnGuestView;
import java.util.List;
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

  /** TAR-205: a failure of the check answers 500 in the BFF shape and never lets the request in. */
  @Test
  void aFailureOfTheCheckAnswers500WithTheBffBodyAndFailsClosed() throws Exception {
    GuestAccountUseCase accounts = mock(GuestAccountUseCase.class);
    OwnGuestUseCase guests = mock(OwnGuestUseCase.class);
    when(accounts.guestOf(1L))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("down jdbc://secret"));
    for (String principal : List.of("1", "not-a-number")) {
      SecurityContextHolder.getContext()
          .setAuthentication(new TestingAuthenticationToken(principal, null));
      var request = new MockHttpServletRequest("GET", "/bff/guest/contract");
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();

      new GuestAreaFilter(accounts, guests).doFilter(request, response, chain);

      assertThat(response.getStatus()).as(principal).isEqualTo(500);
      assertThat(response.getContentType()).startsWith("application/json");
      assertThat(response.getContentAsString())
          .as(principal)
          .isEqualTo(
              "{\"code\":\"INTERNAL_ERROR\","
                  + "\"message\":\"No se pudo completar la acción; vuelve a intentarlo\"}")
          .doesNotContain(principal)
          .doesNotContain("jdbc");
      assertThat(chain.getRequest()).as(principal).isNull();
    }
  }
}
