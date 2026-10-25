package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** TAR-206: uploads per minute and session user, checked before the file is read. */
class UploadRateLimitFilterTest {

  private static final String ROUTE = UploadRoutes.CONTRACTS;
  private static final String REPLACEMENT = "/bff/admin/guests/a1/documents/b2";

  /** Counts every read of the body: a request refused with 429 must not read a byte. */
  private static final class CountingRequest extends MockHttpServletRequest {
    final AtomicInteger reads = new AtomicInteger();

    CountingRequest(String method, String uri) {
      super(method, uri);
    }

    @Override
    public ServletInputStream getInputStream() {
      reads.incrementAndGet();
      return super.getInputStream();
    }

    @Override
    public Collection<Part> getParts() {
      reads.incrementAndGet();
      return List.of();
    }
  }

  private final AtomicInteger chained = new AtomicInteger();

  @AfterEach
  void clearSession() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(String user) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(user, "n/a", "ROLE_ADMIN"));
  }

  private MockHttpServletResponse run(UploadRateLimitFilter filter, MockHttpServletRequest in)
      throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(in, response, (req, res) -> chained.incrementAndGet());
    return response;
  }

  private MockHttpServletResponse send(UploadRateLimitFilter filter, String method, String uri)
      throws Exception {
    return run(filter, new MockHttpServletRequest(method, uri));
  }

  private static UploadRateLimitFilter limitedTo(int perMinute) {
    return new UploadRateLimitFilter(new UploadRateLimitProperties(perMinute));
  }

  @Test
  void letsTheAllowanceThroughAndStopsTheNextUploadWith429WithoutReadingIt() throws Exception {
    var filter = limitedTo(2);
    signIn("7");
    send(filter, "POST", ROUTE);
    send(filter, "POST", ROUTE);

    var refused = new CountingRequest("POST", ROUTE);
    var blocked = run(filter, refused);

    assertThat(chained).hasValue(2);
    assertThat(refused.reads).hasValue(0);
    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 61);
    assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(blocked.getContentType()).startsWith("application/json");
  }

  @Test
  void limitsEveryUploadRouteWithOneAllowancePerUser() throws Exception {
    var filter = limitedTo(6);
    signIn("7");
    for (String route : UploadRoutes.ALL) {
      assertThat(send(filter, "POST", route).getStatus()).isEqualTo(200);
    }
    assertThat(send(filter, "PUT", REPLACEMENT).getStatus()).isEqualTo(200);

    for (String route : UploadRoutes.ALL) {
      assertThat(send(filter, "POST", route).getStatus()).isEqualTo(429);
    }
    assertThat(send(filter, "PUT", REPLACEMENT).getStatus()).isEqualTo(429);
    assertThat(chained).hasValue(6);
  }

  @Test
  void countsEachUserSeparatelyWhateverTheAddress() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    send(filter, "POST", ROUTE);
    var sameUserOtherAddress = new MockHttpServletRequest("POST", ROUTE);
    sameUserOtherAddress.setRemoteAddr("10.0.0.99");
    sameUserOtherAddress.addHeader("CF-Connecting-IP", "203.0.113.9");

    assertThat(run(filter, sameUserOtherAddress).getStatus()).isEqualTo(429);
    signIn("8");
    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
  }

  @Test
  void leavesARequestWithoutSessionToTheSecurity() throws Exception {
    var filter = limitedTo(1);
    send(filter, "POST", ROUTE);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    send(filter, "POST", ROUTE);

    assertThat(chained).hasValue(2);
  }

  @Test
  void matchesOnlyTheExactUploadRoutesAndMethods() throws Exception {
    var filter = limitedTo(1);
    signIn("7");
    send(filter, "POST", ROUTE);

    for (String[] other :
        new String[][] {
          {"GET", ROUTE},
          {"PUT", ROUTE},
          {"POST", ROUTE + "/"},
          {"POST", ROUTE + "/x"},
          {"POST", "/bff/admin/contracts/a1/terminate"},
          {"POST", REPLACEMENT},
          {"GET", REPLACEMENT},
          {"POST", REPLACEMENT + "/deletion"},
          {"PUT", REPLACEMENT + "/x"},
          {"PUT", "/bff/admin/guests/a1/documents"},
          {"POST", "/bff/auth/login"},
        }) {
      assertThat(send(filter, other[0], other[1]).getStatus()).as(other[1]).isEqualTo(200);
    }
  }

  @Test
  void theAllowanceIsAtLeastOne() {
    assertThatThrownBy(() -> new UploadRateLimitProperties(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.rate-limit.uploads-per-minute");
  }
}
