package com.tarimatwasi.quipu.auth.infrastructure.config;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * TAR-300: Resource Server automatically excludes bearer requests from CSRF. Our bearer credential
 * comes from a browser cookie, so that exclusion is unsafe. Apply Spring's standard unsafe-method
 * matcher to the completed filter, after the Resource Server has registered its exclusions.
 */
final class CookieSessionCsrfProtection implements ObjectPostProcessor<CsrfFilter> {

  @Override
  public <O extends CsrfFilter> O postProcess(O filter) {
    filter.setRequireCsrfProtectionMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER);
    return filter;
  }
}
