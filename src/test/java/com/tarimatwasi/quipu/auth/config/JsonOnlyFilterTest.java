package com.tarimatwasi.quipu.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tarimatwasi.quipu.shared.web.UploadRoutes;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * ADR-006 (QP-SPRMONO-CSR-01): a state-changing request takes JSON only, except the exact routes
 * that take a file, and those must say where they come from.
 */
class JsonOnlyFilterTest {

  private static final String ORIGIN = "http://localhost:3000";
  private static final String MULTIPART = "multipart/form-data; boundary=x";

  private final JsonOnlyFilter filter = new JsonOnlyFilter();
  private final AtomicInteger reached = new AtomicInteger();
  private final FilterChain chain = (request, response) -> reached.incrementAndGet();

  private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private static MockHttpServletRequest multipart(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    request.setContentType(MULTIPART);
    request.setContent(new byte[] {1});
    return request;
  }

  @Test
  void theUploadRoutesAreTheFourExactOnes() {
    assertThat(UploadRoutes.ALL)
        .containsExactlyInAnyOrder(
            "/bff/admin/contracts",
            "/bff/admin/payments",
            "/bff/guest/payments",
            "/bff/temporary-guest/payments");
    assertThat(UploadRoutes.VOUCHERS).doesNotContain(UploadRoutes.CONTRACTS).hasSize(3);
  }

  @Test
  void a_multipart_upload_with_an_origin_reaches_every_upload_route() throws Exception {
    for (String route : UploadRoutes.ALL) {
      var request = multipart("POST", route);
      request.addHeader("Origin", ORIGIN);

      assertThat(run(request).getStatus()).as(route).isEqualTo(200);
    }
    assertThat(reached).hasValue(UploadRoutes.ALL.size());
  }

  @Test
  void an_upload_without_origin_is_refused_on_every_upload_route() throws Exception {
    for (String route : UploadRoutes.ALL) {
      var response = run(multipart("POST", route));

      assertThat(response.getStatus()).as(route).isEqualTo(403);
      assertThat(response.getContentAsString()).as(route).contains("AUTH_FORBIDDEN");
    }
    assertThat(reached).hasValue(0);
  }

  @Test
  void a_fetch_the_browser_marks_cross_site_is_refused_even_with_an_origin() throws Exception {
    for (String route : UploadRoutes.ALL) {
      var request = multipart("POST", route);
      request.addHeader("Origin", ORIGIN);
      request.addHeader("Sec-Fetch-Site", "cross-site");

      assertThat(run(request).getStatus()).as(route).isEqualTo(403);
    }
    assertThat(reached).hasValue(0);
  }

  @Test
  void without_origin_only_a_same_origin_fetch_is_let_in() throws Exception {
    for (String route : UploadRoutes.ALL) {
      for (String site : List.of("cross-site", "same-site", "none", "")) {
        var request = multipart("POST", route);
        request.addHeader("Sec-Fetch-Site", site);
        assertThat(run(request).getStatus()).as(route + " " + site).isEqualTo(403);
      }
      var sameOrigin = multipart("POST", route);
      sameOrigin.addHeader("Sec-Fetch-Site", "same-origin");
      assertThat(run(sameOrigin).getStatus()).as(route).isEqualTo(200);
    }
  }

  @Test
  void a_same_site_or_same_origin_fetch_with_an_origin_is_let_in() throws Exception {
    for (String site : List.of("same-origin", "same-site", "none")) {
      var request = multipart("POST", UploadRoutes.CONTRACTS);
      request.addHeader("Origin", ORIGIN);
      request.addHeader("Sec-Fetch-Site", site);

      assertThat(run(request).getStatus()).as(site).isEqualTo(200);
    }
  }

  @Test
  void a_multipart_upload_is_refused_outside_the_upload_routes() throws Exception {
    List<String> others =
        List.of(
            "/bff/admin/environments",
            "/bff/admin/contracts/",
            "/bff/admin/contracts/abc/terminate",
            "/bff/admin/payments/abc/resolve",
            "/bff/admin/payments/",
            "/bff/guest/payments/abc",
            "/bff/guest/contract",
            "/bff/temporary-guest/payment",
            "/bff/auth/login",
            "/bff/admin/Contracts");
    for (String route : others) {
      var request = multipart("POST", route);
      request.addHeader("Origin", ORIGIN);
      var response = run(request);

      assertThat(response.getStatus()).as(route).isEqualTo(415);
      assertThat(response.getContentAsString()).as(route).contains("UNSUPPORTED_MEDIA_TYPE");
    }
    assertThat(reached).hasValue(0);
  }

  @Test
  void only_a_post_to_an_upload_route_may_be_multipart() throws Exception {
    for (String method : List.of("PUT", "PATCH", "DELETE")) {
      var request = multipart(method, UploadRoutes.CONTRACTS);
      request.addHeader("Origin", ORIGIN);

      assertThat(run(request).getStatus()).as(method).isEqualTo(415);
    }
  }

  @Test
  void an_upload_route_takes_nothing_but_multipart_or_json() throws Exception {
    for (String type :
        List.of("text/plain", "application/x-www-form-urlencoded", "multipart/mixed")) {
      var request = new MockHttpServletRequest("POST", UploadRoutes.CONTRACTS);
      request.setRequestURI(UploadRoutes.CONTRACTS);
      request.setContentType(type);
      request.setContent(new byte[] {1});
      request.addHeader("Origin", ORIGIN);

      assertThat(run(request).getStatus()).as(type).isEqualTo(415);
    }
    var json = new MockHttpServletRequest("POST", UploadRoutes.CONTRACTS);
    json.setRequestURI(UploadRoutes.CONTRACTS);
    json.setContentType("application/json");
    json.setContent(new byte[] {1});
    assertThat(run(json).getStatus()).isEqualTo(200);
  }

  @Test
  void a_bodyless_request_and_a_get_are_not_touched() throws Exception {
    var bodyless = new MockHttpServletRequest("POST", "/bff/admin/contracts/abc/terminate");
    bodyless.setRequestURI("/bff/admin/contracts/abc/terminate");
    var get = new MockHttpServletRequest("GET", UploadRoutes.CONTRACTS);
    get.setRequestURI(UploadRoutes.CONTRACTS);

    assertThat(run(bodyless).getStatus()).isEqualTo(200);
    assertThat(run(get).getStatus()).isEqualTo(200);
  }
}
