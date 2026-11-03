package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class BffCallerTest {

  @Test
  void theAreaOfTheRouteSaysWhoIsAnswered() {
    assertThat(of("POST", "/bff/guest/payments")).isEqualTo(BffCaller.CONTRACT_GUEST);
    assertThat(of("PUT", "/bff/guest/payments/abc")).isEqualTo(BffCaller.CONTRACT_GUEST);
    assertThat(of("POST", "/bff/temporary-guest/payments")).isEqualTo(BffCaller.TEMPORARY_GUEST);
    assertThat(of("POST", "/bff/admin/payments")).isEqualTo(BffCaller.ADMINISTRATION);
    assertThat(of("POST", "/bff/admin/guests/abc/stays")).isEqualTo(BffCaller.ADMINISTRATION);
  }

  /** A route that only starts like a guest area is not one. */
  @Test
  void aRouteThatOnlySharesThePrefixIsNotAGuestArea() {
    assertThat(of("POST", "/bff/guests/payments")).isEqualTo(BffCaller.ADMINISTRATION);
    assertThat(of("POST", "/bff/temporary-guests/payments")).isEqualTo(BffCaller.ADMINISTRATION);
  }

  @Test
  void onlyTheAdministrationIsNotAGuest() {
    assertThat(BffCaller.ADMINISTRATION.guest()).isFalse();
    assertThat(BffCaller.CONTRACT_GUEST.guest()).isTrue();
    assertThat(BffCaller.TEMPORARY_GUEST.guest()).isTrue();
  }

  private static BffCaller of(String method, String path) {
    return BffCaller.of(new MockHttpServletRequest(method, path));
  }
}
