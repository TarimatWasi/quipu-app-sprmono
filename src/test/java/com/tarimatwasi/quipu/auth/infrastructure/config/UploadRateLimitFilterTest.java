package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import java.io.IOException;
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
      throws IOException, ServletException {
    var response = new MockHttpServletResponse();
    filter.doFilter(in, response, (req, res) -> chained.incrementAndGet());
    return response;
  }

  private MockHttpServletResponse send(UploadRateLimitFilter filter, String method, String uri)
      throws IOException, ServletException {
    return run(filter, new MockHttpServletRequest(method, uri));
  }

  /** A burst as large as the allowance of the minute: only the count per minute matters. */
  private static UploadRateLimitFilter limitedTo(int perMinute) {
    return limitedTo(perMinute, perMinute);
  }

  private static UploadRateLimitFilter limitedTo(int perMinute, int burst) {
    return new UploadRateLimitFilter(new UploadRateLimitProperties(perMinute, burst));
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
  void theBurstIsSmallerThanTheAllowanceOfTheMinute() throws Exception {
    var filter = limitedTo(10, 3);
    signIn("7");
    for (int i = 0; i < 3; i++) {
      assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
    }

    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(429);
  }

  /** One upload in progress per user: a second one at once waits for nothing, it gets 429. */
  @Test
  void aSecondUploadOfTheSameUserAtOnceGets429WithoutReadingItWhileAnotherUserPasses()
      throws Exception {
    var filter = limitedTo(10);
    var concurrent = new CountingRequest("POST", ROUTE);
    var answers = new java.util.ArrayList<MockHttpServletResponse>();
    signIn("7");

    filter.doFilter(
        new MockHttpServletRequest("POST", ROUTE),
        new MockHttpServletResponse(),
        (req, res) -> {
          answers.add(run(filter, concurrent));
          signIn("8");
          answers.add(send(filter, "POST", ROUTE));
          signIn("7");
        });

    assertThat(answers.get(0).getStatus()).isEqualTo(429);
    assertThat(answers.get(0).getHeader("Retry-After")).isEqualTo("1");
    assertThat(answers.get(0).getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(concurrent.reads).hasValue(0);
    assertThat(answers.get(1).getStatus()).isEqualTo(200);
    // once the first one ends, the same user uploads again
    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
  }

  @Test
  void aRefusedConcurrentUploadSpendsNoAllowance() throws Exception {
    var filter = limitedTo(2);
    signIn("7");
    filter.doFilter(
        new MockHttpServletRequest("POST", ROUTE),
        new MockHttpServletResponse(),
        (req, res) -> send(filter, "POST", ROUTE));

    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
  }

  @Test
  void theUploadInProgressIsReleasedWhenTheRequestFails() throws Exception {
    var filter = limitedTo(10);
    signIn("7");

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("POST", ROUTE),
                    new MockHttpServletResponse(),
                    (req, res) -> {
                      throw new IOException("the store failed");
                    }))
        .isInstanceOf(IOException.class);

    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
  }

  @Test
  void anAsyncUploadStaysInProgressUntilItCompletes() throws Exception {
    var filter = limitedTo(10);
    signIn("7");
    var async = new MockHttpServletRequest("POST", ROUTE);
    async.setAsyncSupported(true);
    var started = new java.util.concurrent.atomic.AtomicReference<AsyncContext>();
    filter.doFilter(
        async, new MockHttpServletResponse(), (req, res) -> started.set(req.startAsync()));

    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(429);
    started.get().complete();
    assertThat(send(filter, "POST", ROUTE).getStatus()).isEqualTo(200);
  }

  /**
   * TAR-205: the container may end an async upload twice (a timeout or an error, then complete).
   * The second end must not take away the permit of the next upload of the same user.
   */
  @Test
  void anAsyncUploadEndedTwiceReleasesOnlyItsOwnPermit() throws Exception {
    var filter = limitedTo(10);
    signIn("7");
    var first = new MockHttpServletRequest("POST", ROUTE);
    first.setAsyncSupported(true);
    var started = new java.util.concurrent.atomic.AtomicReference<AsyncContext>();
    filter.doFilter(
        first, new MockHttpServletResponse(), (req, res) -> started.set(req.startAsync()));
    var context = (org.springframework.mock.web.MockAsyncContext) started.get();
    var event = new jakarta.servlet.AsyncEvent(context);
    for (var listener : context.getListeners()) {
      listener.onTimeout(event);
    }

    var answers = new java.util.ArrayList<MockHttpServletResponse>();
    filter.doFilter(
        new MockHttpServletRequest("POST", ROUTE),
        new MockHttpServletResponse(),
        (req, res) -> {
          // the first upload now completes too, while the second one is in progress
          context.complete();
          answers.add(send(filter, "POST", ROUTE));
        });

    assertThat(answers.getFirst().getStatus()).isEqualTo(429);
  }

  @Test
  void theAllowanceAndTheBurstAreBounded() {
    for (int[] wrong : new int[][] {{0, 1}, {61, 3}, {10, 0}, {10, 11}}) {
      assertThatThrownBy(() -> new UploadRateLimitProperties(wrong[0], wrong[1]))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("app.rate-limit.");
    }
    assertThat(new UploadRateLimitProperties(60, 60).uploadsPerMinute()).isEqualTo(60);
  }
}
