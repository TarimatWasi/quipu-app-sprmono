package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestState;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase.OwnGuestView;
import com.tarimatwasi.quipu.payment.port.in.OnboardingRequiredException;
import com.tarimatwasi.quipu.payment.port.in.OtherGuestTypeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The one guard of the guest areas (RN-09, RN-33): every route of /bff/guest/** and
 * /bff/temporary-guest/** but the onboarding ones (see {@link GuestAreaWebConfig}) is refused with
 * {@code AUTH_FORBIDDEN} to a guest of the other type, or to an account of no guest, and with
 * {@code AUTH_ONBOARDING_REQUIRED} to a guest that has not completed the onboarding. The security
 * filters already let only a GUEST session in; this checks the guest behind it, before the request
 * is read. The answers come from {@link BffExceptionHandler}.
 */
@Component
class GuestAreaInterceptor implements HandlerInterceptor {

  private static final String TEMPORARY_AREA = "/bff/temporary-guest/";

  private final GuestAccountUseCase accounts;
  private final OwnGuestUseCase guests;

  GuestAreaInterceptor(GuestAccountUseCase accounts, OwnGuestUseCase guests) {
    this.accounts = accounts;
    this.guests = guests;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      // Never reached: the security filters answer a request without a session first.
      throw new OtherGuestTypeException();
    }
    Long guestId =
        accounts
            .guestOf(Long.valueOf(authentication.getName()))
            .orElseThrow(OtherGuestTypeException::new);
    OwnGuestView guest = guests.own(guestId);
    String path = request.getRequestURI().substring(request.getContextPath().length());
    GuestKind area = path.startsWith(TEMPORARY_AREA) ? GuestKind.TEMPORARY : GuestKind.CONTRACT;
    if (guest.type() != area) {
      throw new OtherGuestTypeException();
    }
    if (guest.status() == GuestState.PENDING_ACTIVATION
        || guest.status() == GuestState.ONBOARDING) {
      throw new OnboardingRequiredException();
    }
    return true;
  }
}
