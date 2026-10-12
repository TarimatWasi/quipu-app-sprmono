package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SEC-01: 10 requests per minute and client on login and password recovery. */
class RateLimitFilterTest {

  private static final String LOGIN = "/bff/auth/login";
  private static final String SECRET = "origin-secret-for-tests";

  private static MockHttpServletRequest post(String uri, String address) {
    var request = new MockHttpServletRequest("POST", uri);
    request.setServletPath(uri);
    request.setRemoteAddr(address);
    return request;
  }

  private static MockHttpServletResponse run(RateLimitFilter filter, MockHttpServletRequest in)
      throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(in, response, new MockFilterChain());
    return response;
  }

  private static RateLimitFilter limitedTo(int perMinute, String header) {
    return new RateLimitFilter(new RateLimitProperties(perMinute, header, SECRET));
  }

  private static MockHttpServletRequest viaCloudflare(String secret, String clientIp) {
    var request = post(LOGIN, "10.9.9.9");
    request.addHeader("CF-Connecting-IP", clientIp);
    if (!secret.isEmpty()) {
      request.addHeader("X-Origin-Secret", secret);
    }
    return request;
  }

  @Test
  void letsTheAllowanceThroughAndStopsTheNextRequestWith429() throws Exception {
    var filter = limitedTo(3, "");

    for (int i = 0; i < 3; i++) {
      assertThat(run(filter, post(LOGIN, "10.0.0.1")).getStatus()).isEqualTo(200);
    }
    var blocked = run(filter, post(LOGIN, "10.0.0.1"));

    assertThat(blocked.getStatus()).isEqualTo(429);
    assertThat(Integer.parseInt(blocked.getHeader("Retry-After"))).isBetween(1, 61);
    assertThat(blocked.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(blocked.getContentType()).startsWith("application/json");
  }

  @Test
  void countsEachClientSeparately() throws Exception {
    var filter = limitedTo(1, "");
    run(filter, post(LOGIN, "10.0.0.1"));

    assertThat(run(filter, post(LOGIN, "10.0.0.1")).getStatus()).isEqualTo(429);
    assertThat(run(filter, post(LOGIN, "10.0.0.2")).getStatus()).isEqualTo(200);
  }

  @Test
  void limitsPasswordRecoveryToo() throws Exception {
    var filter = limitedTo(1, "");
    run(filter, post("/bff/auth/forgot-password", "10.0.0.1"));

    assertThat(run(filter, post("/bff/auth/forgot-password", "10.0.0.1")).getStatus())
        .isEqualTo(429);
    assertThat(run(filter, post("/bff/auth/reset-password", "10.0.0.1")).getStatus())
        .isEqualTo(429);
  }

  @Test
  void sharesTheAllowanceAcrossTheSensitiveEndpoints() throws Exception {
    var filter = limitedTo(2, "");
    run(filter, post(LOGIN, "10.0.0.1"));
    run(filter, post("/bff/auth/forgot-password", "10.0.0.1"));

    assertThat(run(filter, post(LOGIN, "10.0.0.1")).getStatus()).isEqualTo(429);
  }

  @Test
  void leavesOtherEndpointsAndMethodsAlone() throws Exception {
    var filter = limitedTo(1, "");
    run(filter, post(LOGIN, "10.0.0.1"));

    assertThat(run(filter, post("/bff/auth/change-password", "10.0.0.1")).getStatus())
        .isEqualTo(200);
    var get = new MockHttpServletRequest("GET", LOGIN);
    get.setRemoteAddr("10.0.0.1");
    assertThat(run(filter, get).getStatus()).isEqualTo(200);
  }

  @Test
  void tellsClientsApartByTheConfiguredHeader() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");
    var first = post(LOGIN, "10.9.9.9");
    first.addHeader("CF-Connecting-IP", "203.0.113.5, 10.1.1.1");
    first.addHeader("X-Origin-Secret", SECRET);
    var second = post(LOGIN, "10.9.9.9");
    second.addHeader("CF-Connecting-IP", "203.0.113.6");
    second.addHeader("X-Origin-Secret", SECRET);
    var again = post(LOGIN, "10.9.9.9");
    again.addHeader("CF-Connecting-IP", "203.0.113.5");
    again.addHeader("X-Origin-Secret", SECRET);

    assertThat(run(filter, first).getStatus()).isEqualTo(200);
    assertThat(run(filter, second).getStatus()).isEqualTo(200);
    assertThat(run(filter, again).getStatus()).isEqualTo(429);
  }

  /** The container reports the raw URI; the servlet path is the decoded one that gets routed. */
  @Test
  void aPercentEncodedPathDoesNotEscapeTheLimit() throws Exception {
    var filter = limitedTo(1, "");
    run(filter, post(LOGIN, "10.0.0.1"));
    var encoded = post("/bff/auth/%6Cogin", "10.0.0.1");
    encoded.setServletPath(LOGIN);

    assertThat(run(filter, encoded).getStatus()).isEqualTo(429);
  }

  @Test
  void aHeaderThatIsNotAnAddressDoesNotChooseTheBucket() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");
    var first = post(LOGIN, "10.9.9.9");
    first.addHeader("CF-Connecting-IP", "not-an-address-" + "x".repeat(500));
    first.addHeader("X-Origin-Secret", SECRET);
    var second = post(LOGIN, "10.9.9.9");
    second.addHeader("CF-Connecting-IP", "another-free-text");
    second.addHeader("X-Origin-Secret", SECRET);
    second.addHeader("X-Origin-Secret", SECRET);

    assertThat(run(filter, first).getStatus()).isEqualTo(200);
    assertThat(run(filter, second).getStatus()).isEqualTo(429);
  }

  @Test
  void fallsBackToTheConnectionAddressWhenTheHeaderIsMissing() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");
    run(filter, post(LOGIN, "10.9.9.9"));

    assertThat(run(filter, post(LOGIN, "10.9.9.9")).getStatus()).isEqualTo(429);
  }

  @Test
  void withTheOriginSecretEachCloudflareAddressHasItsOwnCounter() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");

    assertThat(run(filter, viaCloudflare(SECRET, "203.0.113.1")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare(SECRET, "203.0.113.2")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare(SECRET, "203.0.113.1")).getStatus()).isEqualTo(429);
  }

  @Test
  void withoutTheOriginSecretARotatingHeaderCountsAsTheRemoteAddress() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");

    assertThat(run(filter, viaCloudflare("", "203.0.113.1")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare("", "203.0.113.2")).getStatus()).isEqualTo(429);
  }

  @Test
  void aWrongOriginSecretCountsAsTheRemoteAddress() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");

    assertThat(run(filter, viaCloudflare("wrong", "203.0.113.1")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare("wrong-again", "203.0.113.2")).getStatus()).isEqualTo(429);
  }

  @Test
  void aSecretOfAnotherLengthOrAPrefixOfTheRealOneDoesNotMatch() throws Exception {
    var filter = limitedTo(1, "CF-Connecting-IP");

    assertThat(run(filter, viaCloudflare(SECRET + "x", "203.0.113.1")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare(SECRET.substring(1), "203.0.113.2")).getStatus())
        .isEqualTo(429);
  }

  /** A blank secret would silently turn the per-address limit into one shared bucket (TAR-180). */
  @Test
  void aBlankSecretWithTheClientIpHeaderIsRefused() {
    for (String blank : new String[] {"", " ", "   "}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> new RateLimitProperties(60, "CF-Connecting-IP", blank))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void withoutTheHeaderNorTheSecretTheConnectionAddressIsUsed() throws Exception {
    var filter = new RateLimitFilter(new RateLimitProperties(1, "", ""));

    assertThat(run(filter, viaCloudflare("", "203.0.113.1")).getStatus()).isEqualTo(200);
    assertThat(run(filter, viaCloudflare("", "203.0.113.2")).getStatus()).isEqualTo(429);
  }

  @Test
  void anUnresolvedPlaceholderIsNotAcceptedAsTheSecret() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new RateLimitProperties(60, "CF-Connecting-IP", "${ORIGIN_SECRET}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
