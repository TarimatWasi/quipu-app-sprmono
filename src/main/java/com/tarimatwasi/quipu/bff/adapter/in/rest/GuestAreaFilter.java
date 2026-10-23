package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.GuestNotFoundException;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase.OwnGuestView;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The one guard of the guest areas (RN-09, RN-33): every route of /bff/guest/** and
 * /bff/temporary-guest/** but the onboarding ones is refused with {@code AUTH_FORBIDDEN} to a guest
 * of the other type, or to an account of no guest, and with {@code AUTH_ONBOARDING_REQUIRED} to a
 * guest that has not completed the onboarding.
 *
 * <p>It is a servlet filter, after the security filters (which already let only a GUEST session in)
 * and before the dispatcher: the dispatcher reads a multipart form before any interceptor, so the
 * guard answers before the body of an upload is read or its size limit is raised. The body is
 * written by hand, as the security filters do, because no controller advice runs here. Each area
 * has its type of guest fixed and is matched against the parsed path, as the routes are: never
 * against the raw text of the URI, which can be encoded differently.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class GuestAreaFilter extends OncePerRequestFilter {

  private static final PathPatternRequestMatcher.Builder PATHS =
      PathPatternRequestMatcher.withDefaults();

  private static final RequestMatcher CONTRACT_AREA =
      new AndRequestMatcher(
          PATHS.matcher("/bff/guest/**"),
          new NegatedRequestMatcher(
              new OrRequestMatcher(
                  PATHS.matcher("/bff/guest/onboarding"),
                  PATHS.matcher("/bff/guest/onboarding/**"))));

  private static final RequestMatcher TEMPORARY_AREA = PATHS.matcher("/bff/temporary-guest/**");

  private static final String FORBIDDEN_BODY =
      "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"No tienes permiso para esta acción\"}";

  private static final String ONBOARDING_REQUIRED_BODY =
      "{\"code\":\"AUTH_ONBOARDING_REQUIRED\",\"message\":\"Completa tu registro para continuar\"}";

  private final GuestAccountUseCase accounts;
  private final OwnGuestUseCase guests;

  GuestAreaFilter(GuestAccountUseCase accounts, OwnGuestUseCase guests) {
    this.accounts = accounts;
    this.guests = guests;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    GuestKind area = areaOf(request);
    String refusal = area == null ? null : refusal(area);
    if (refusal == null) {
      chain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(refusal);
  }

  private static @Nullable GuestKind areaOf(HttpServletRequest request) {
    if (CONTRACT_AREA.matches(request)) {
      return GuestKind.CONTRACT;
    }
    return TEMPORARY_AREA.matches(request) ? GuestKind.TEMPORARY : null;
  }

  /** The body of the refusal, or null when the guest of the session may use the area. */
  private @Nullable String refusal(GuestKind area) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      // Never reached: the security filters answer a request without a session first.
      return FORBIDDEN_BODY;
    }
    Long guestId = accounts.guestOf(Long.valueOf(authentication.getName())).orElse(null);
    if (guestId == null) {
      return FORBIDDEN_BODY;
    }
    OwnGuestView guest;
    try {
      guest = guests.own(guestId);
    } catch (GuestNotFoundException e) {
      return FORBIDDEN_BODY;
    }
    if (guest.type() != area) {
      return FORBIDDEN_BODY;
    }
    if (guest.status() == GuestState.PENDING_ACTIVATION
        || guest.status() == GuestState.ONBOARDING) {
      return ONBOARDING_REQUIRED_BODY;
    }
    return null;
  }
}
