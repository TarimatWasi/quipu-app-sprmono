package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Who a route of the BFF answers, by the area of the route (TAR-247): the catalog of SRS 17 gives
 * some codes one text for the administration and another for the guest, with the same code.
 */
enum BffCaller {
  /** Any route outside the areas of the guests. */
  ADMINISTRATION,
  /** A guest with a contract, under /bff/guest/** (H3). */
  CONTRACT_GUEST,
  /** A temporary guest, under /bff/temporary-guest/** (T2). */
  TEMPORARY_GUEST;

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();
  private static final RequestMatcher CONTRACT_AREA = PATHS.matcher("/bff/guest/**");
  private static final RequestMatcher TEMPORARY_AREA = PATHS.matcher("/bff/temporary-guest/**");

  /** The caller of the request being answered. */
  static BffCaller of(HttpServletRequest request) {
    if (CONTRACT_AREA.matches(request)) {
      return CONTRACT_GUEST;
    }
    if (TEMPORARY_AREA.matches(request)) {
      return TEMPORARY_GUEST;
    }
    return ADMINISTRATION;
  }

  /** Whether it is a guest of either type. */
  boolean guest() {
    return this != ADMINISTRATION;
  }
}
