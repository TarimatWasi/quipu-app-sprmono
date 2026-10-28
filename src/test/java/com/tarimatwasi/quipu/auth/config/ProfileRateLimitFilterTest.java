package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** TAR-222: the edits of the own profile of a session user, both routes in one counter. */
class ProfileRateLimitFilterTest {

  private static final String CONTRACT = "/bff/guest/profile";
  private static final String TEMPORARY = "/bff/temporary-guest/profile";

  private final AtomicInteger chained = new AtomicInteger();

  @AfterEach
  void clearSession() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(String user) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(user, "n/a", "ROLE_GUEST"));
  }

  private MockHttpServletResponse send(ProfileRateLimitFilter filter, String method, String uri)
      throws IOException, ServletException {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest(method, uri), response, (req, res) -> chained.incrementAndGet());
    return response;
  }

  private static ProfileRateLimitFilter limitedTo(int perMinute) {
    return new ProfileRateLimitFilter(new ProfileRateLimitProperties(perMinute));
  }

  private static void assertLimited(MockHttpServletResponse response) throws IOException {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(response.getHeader("Retry-After")).isNotNull();
  }

  @Test
  void bothRoutesShareTheAllowanceAndTheNextEditGets429() throws Exception {
    var filter = limitedTo(2);
    signIn("7");

    assertThat(send(filter, "PATCH", CONTRACT).getStatus()).isEqualTo(200);
    assertThat(send(filter, "PATCH", TEMPORARY).getStatus()).isEqualTo(200);

    assertLimited(send(filter, "PATCH", CONTRACT));
    assertLimited(send(filter, "PATCH", TEMPORARY));
    assertThat(chained).hasValue(2);
  }

  @Test
  void theAllowanceIsPerUser() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    send(filter, "PATCH", CONTRACT);
    assertLimited(send(filter, "PATCH", CONTRACT));

    signIn("8");
    assertThat(send(filter, "PATCH", CONTRACT).getStatus()).isEqualTo(200);
    assertThat(chained).hasValue(2);
  }

  @Test
  void otherMethodsAndOtherRoutesNeitherCountNorAreLimited() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "GET", CONTRACT).getStatus()).isEqualTo(200);
      assertThat(send(filter, "PATCH", "/bff/guest/payments").getStatus()).isEqualTo(200);
      assertThat(send(filter, "PATCH", "/bff/admin/guests/x").getStatus()).isEqualTo(200);
    }

    assertThat(send(filter, "PATCH", CONTRACT).getStatus()).isEqualTo(200);
    assertLimited(send(filter, "PATCH", TEMPORARY));
    assertThat(chained).hasValue(10);
  }

  @Test
  void aRequestWithoutSessionPassesUntouched() throws Exception {
    var filter = limitedTo(1);
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "PATCH", CONTRACT).getStatus()).isEqualTo(200);
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "PATCH", CONTRACT).getStatus()).isEqualTo(200);
    }
    assertThat(chained).hasValue(6);
  }
}
