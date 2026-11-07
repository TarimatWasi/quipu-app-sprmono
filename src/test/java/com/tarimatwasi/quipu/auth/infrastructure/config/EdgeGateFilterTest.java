package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** TAR-144, TAR-9: only what Vercel forwards reaches /bff, and the address limit keys on its IP. */
class EdgeGateFilterTest {

  private static final String SECRET = "a".repeat(32);
  private static final String PREVIOUS = "b".repeat(40);

  /** The endpoint behind the filters: it answers 200 to any method. */
  private static final class Endpoint extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) {
      // 200 and nothing else
    }
  }

  private static EdgeProperties props(List<String> secrets) {
    return new EdgeProperties(secrets, false);
  }

  private static EdgeGateFilter gate(String... secrets) {
    return new EdgeGateFilter(props(List.of(secrets)));
  }

  private static MockHttpServletRequest request(String method, String uri) {
    var request = new MockHttpServletRequest(method, uri);
    request.setServletPath(uri);
    request.setRemoteAddr("10.9.9.9");
    return request;
  }

  private static MockHttpServletResponse run(
      EdgeGateFilter filter, MockHttpServletRequest in, MockFilterChain chain) throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(in, response, chain);
    return response;
  }

  @Test
  void aRequestWithoutTheSecretIsRefusedBeforeAnyOtherFilter() throws Exception {
    var chain = new MockFilterChain();
    var response = run(gate(SECRET), request("POST", "/bff/auth/login"), chain);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"code\":\"AUTH_FORBIDDEN\"");
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aWrongSecretIsRefused() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, "c".repeat(32));
    var chain = new MockFilterChain();

    assertThat(run(gate(SECRET), in, chain).getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aSecretOfAnotherLengthOrAPrefixIsRefused() throws Exception {
    for (String sent : List.of("a".repeat(31), "a".repeat(33), SECRET + "x", "")) {
      var in = request("GET", "/bff/auth/csrf");
      in.addHeader(EdgeGateFilter.SECRET_HEADER, sent);

      assertThat(run(gate(SECRET), in, new MockFilterChain()).getStatus()).isEqualTo(403);
    }
  }

  @Test
  void theRightSecretPassesAndKeepsTheRequestForTheNextFilter() throws Exception {
    var in = request("POST", "/bff/auth/login");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
    var chain = new MockFilterChain();

    assertThat(run(gate(SECRET), in, chain).getStatus()).isEqualTo(200);
    assertThat(chain.getRequest()).isSameAs(in);
  }

  @Test
  void duringARotationBothSecretsPass() throws Exception {
    var filter = gate(SECRET, PREVIOUS);
    for (String secret : List.of(SECRET, PREVIOUS)) {
      var in = request("GET", "/bff/auth/csrf");
      in.addHeader(EdgeGateFilter.SECRET_HEADER, secret);

      assertThat(run(filter, in, new MockFilterChain()).getStatus()).isEqualTo(200);
    }
  }

  @Test
  void aVisitorCannotSendAnAddressWithoutTheSecret() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "203.0.113.7");
    var chain = new MockFilterChain();

    assertThat(run(gate(SECRET), in, chain).getStatus()).isEqualTo(403);
    assertThat(in.getAttribute(EdgeGateFilter.CLIENT_ADDRESS)).isNull();
  }

  @Test
  void withTheSecretTheAddressOfTheBrowserIsTheFirstOfTheVercelHeader() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
    in.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "203.0.113.7, 198.51.100.1");

    run(gate(SECRET), in, new MockFilterChain());

    assertThat(in.getAttribute(EdgeGateFilter.CLIENT_ADDRESS)).isEqualTo("203.0.113.7");
  }

  @Test
  void anIpv6AddressIsKept() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
    in.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "2001:db8::1");

    run(gate(SECRET), in, new MockFilterChain());

    assertThat(in.getAttribute(EdgeGateFilter.CLIENT_ADDRESS)).isEqualTo("2001:db8::1");
  }

  @Test
  void aFreeTextAddressIsNotAKey() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
    in.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "not an address, <script>");

    run(gate(SECRET), in, new MockFilterChain());

    assertThat(in.getAttribute(EdgeGateFilter.CLIENT_ADDRESS)).isNull();
  }

  @Test
  void withoutTheVercelAddressTheRequestStillPassesWithoutAnAttribute() throws Exception {
    var in = request("GET", "/bff/auth/csrf");
    in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);

    assertThat(run(gate(SECRET), in, new MockFilterChain()).getStatus()).isEqualTo(200);
    assertThat(in.getAttribute(EdgeGateFilter.CLIENT_ADDRESS)).isNull();
  }

  @Test
  void theHealthCheckAndEverythingOutsideBffStayOpen() throws Exception {
    for (String uri : List.of("/actuator/health", "/actuator/health/liveness", "/error")) {
      var chain = new MockFilterChain();

      assertThat(run(gate(SECRET), request("GET", uri), chain).getStatus()).isEqualTo(200);
      assertThat(chain.getRequest()).isNotNull();
    }
  }

  @Test
  void everyMethodUnderBffNeedsTheSecret() throws Exception {
    for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
      assertThat(
              run(gate(SECRET), request(method, "/bff/admin/guests"), new MockFilterChain())
                  .getStatus())
          .as(method)
          .isEqualTo(403);
    }
  }

  @Test
  void aPathLikeBffButNotUnderItIsNotGated() throws Exception {
    assertThat(run(gate(SECRET), request("GET", "/bffx/y"), new MockFilterChain()).getStatus())
        .isEqualTo(200);
  }

  @Test
  void theRateLimitKeysOnTheVerifiedBrowserAddressNotOnTheHeaderOfTheConnection() throws Exception {
    var gate = gate(SECRET);
    var limiter = new RateLimitFilter(new RateLimitProperties(1, "CF-Connecting-IP"));

    // Two browsers behind the same Vercel egress address: each has its own allowance.
    for (String browser : List.of("203.0.113.1", "203.0.113.2")) {
      var in = request("POST", "/bff/auth/login");
      in.addHeader("CF-Connecting-IP", "76.76.21.21");
      in.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
      in.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, browser);
      var out = new MockHttpServletResponse();
      gate.doFilter(in, out, new MockFilterChain(new Endpoint(), limiter));
      assertThat(out.getStatus()).as(browser).isEqualTo(200);
    }
    // The same browser again: limited, however many addresses Vercel leaves from.
    var again = request("POST", "/bff/auth/login");
    again.addHeader("CF-Connecting-IP", "76.76.21.99");
    again.addHeader(EdgeGateFilter.SECRET_HEADER, SECRET);
    again.addHeader(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "203.0.113.1");
    var blocked = new MockHttpServletResponse();
    gate.doFilter(again, blocked, new MockFilterChain(new Endpoint(), limiter));

    assertThat(blocked.getStatus()).isEqualTo(429);
  }

  @Test
  void theSecretsAreValidatedAtStartup() {
    assertThatThrownBy(() -> props(List.of("short"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(List.of("a".repeat(31))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(List.of("a".repeat(129))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(List.of("a".repeat(16) + " " + "a".repeat(16))))
        .as("a space inside is not stripped away")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(List.of("a".repeat(30) + "é!")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> props(List.of(SECRET, PREVIOUS, "c".repeat(32))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** TAR-144: dev and prod never run with the gate off, not even with an empty variable. */
  @Test
  void aRequiredGateRefusesAnEmptyValue() {
    assertThatThrownBy(() -> new EdgeProperties(List.of(""), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EDGE_SHARED_SECRETS");
    assertThatThrownBy(() -> new EdgeProperties(List.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new EdgeProperties(List.of(SECRET), true).enabled()).isTrue();
  }

  @Test
  void noSecretsMeansTheGateIsOff() {
    assertThat(props(List.of()).enabled()).isFalse();
    assertThat(props(List.of("", "  ")).enabled()).isFalse();
    assertThat(props(List.of(SECRET)).enabled()).isTrue();
  }
}
