package com.tarimatwasi.quipu.bff.adapter.in.rest;

import com.tarimatwasi.quipu.auth.port.in.GuestAccountUseCase;
import com.tarimatwasi.quipu.guest.port.in.ManageGuestsUseCase.GuestKind;
import com.tarimatwasi.quipu.guest.port.in.OwnGuestUseCase;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where {@link GuestAreaInterceptor} runs: each guest area with its own type of guest, except the
 * onboarding (RN-33). The patterns are matched against the parsed path, as the routes are.
 */
@Configuration(proxyBeanMethods = false)
class GuestAreaWebConfig implements WebMvcConfigurer {

  private final GuestAccountUseCase accounts;
  private final OwnGuestUseCase guests;

  GuestAreaWebConfig(GuestAccountUseCase accounts, OwnGuestUseCase guests) {
    this.accounts = accounts;
    this.guests = guests;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(new GuestAreaInterceptor(GuestKind.CONTRACT, accounts, guests))
        .addPathPatterns("/bff/guest/**")
        .excludePathPatterns("/bff/guest/onboarding", "/bff/guest/onboarding/**");
    registry
        .addInterceptor(new GuestAreaInterceptor(GuestKind.TEMPORARY, accounts, guests))
        .addPathPatterns("/bff/temporary-guest/**");
  }
}
