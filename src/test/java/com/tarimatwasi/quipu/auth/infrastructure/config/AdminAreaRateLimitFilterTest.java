package com.tarimatwasi.quipu.auth.infrastructure.config;

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

/** TAR-221: every request of a session user to /bff/admin/** counts, whatever the method. */
class AdminAreaRateLimitFilterTest {

  private final AtomicInteger chained = new AtomicInteger();

  @AfterEach
  void clearSession() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(String user) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(user, "n/a", "ROLE_ADMIN"));
  }

  private MockHttpServletResponse send(AdminAreaRateLimitFilter filter, String method, String uri)
      throws IOException, ServletException {
    var response = new MockHttpServletResponse();
    filter.doFilter(
        new MockHttpServletRequest(method, uri), response, (req, res) -> chained.incrementAndGet());
    return response;
  }

  private static AdminAreaRateLimitFilter limitedTo(int perMinute) {
    return new AdminAreaRateLimitFilter(new AdminAreaRateLimitProperties(perMinute));
  }

  private static void assertLimited(MockHttpServletResponse response) throws IOException {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(response.getHeader("Retry-After")).isNotNull();
    assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 61L);
  }

  @Test
  void anyMethodAndAnyAdminRouteSharesTheAllowanceAndTheNextOneGets429() throws Exception {
    var filter = limitedTo(4);
    signIn("7");

    assertThat(send(filter, "GET", "/bff/admin/expenses").getStatus()).isEqualTo(200);
    assertThat(send(filter, "POST", "/bff/admin/fines").getStatus()).isEqualTo(200);
    assertThat(send(filter, "PATCH", "/bff/admin/expenses/x").getStatus()).isEqualTo(200);
    assertThat(send(filter, "DELETE", "/bff/admin/fines/y").getStatus()).isEqualTo(200);

    assertLimited(send(filter, "GET", "/bff/admin/expenses"));
    assertLimited(send(filter, "POST", "/bff/admin/guests/a/documents/b/deletion"));
    assertThat(chained).hasValue(4);
  }

  @Test
  void theAllowanceIsPerUser() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    send(filter, "GET", "/bff/admin/expenses");
    assertLimited(send(filter, "GET", "/bff/admin/expenses"));

    signIn("8");
    assertThat(send(filter, "GET", "/bff/admin/expenses").getStatus()).isEqualTo(200);
    assertThat(chained).hasValue(2);
  }

  @Test
  void routesOutsideTheAdminAreaNeitherCountNorAreLimited() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    for (var uri : new String[] {"/bff/guest/payments", "/bff/auth/me", "/bff/administrator"}) {
      for (int i = 0; i < 3; i++) {
        assertThat(send(filter, "GET", uri).getStatus()).as(uri).isEqualTo(200);
      }
    }

    assertThat(send(filter, "GET", "/bff/admin/expenses").getStatus()).isEqualTo(200);
    assertLimited(send(filter, "GET", "/bff/admin/expenses"));
    assertThat(chained).hasValue(10);
  }

  @Test
  void aRequestWithoutSessionPassesUntouchedAndIsNotCountedByAddress() throws Exception {
    var filter = limitedTo(1);
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "GET", "/bff/admin/expenses").getStatus()).isEqualTo(200);
    }
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "GET", "/bff/admin/expenses").getStatus()).isEqualTo(200);
    }
    assertThat(chained).hasValue(6);
  }
}
