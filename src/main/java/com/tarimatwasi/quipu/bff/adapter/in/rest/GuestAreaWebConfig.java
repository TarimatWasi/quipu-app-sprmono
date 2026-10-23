package com.tarimatwasi.quipu.bff.adapter.in.rest;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Where {@link GuestAreaInterceptor} runs: the guest areas, except the onboarding (RN-33). */
@Configuration(proxyBeanMethods = false)
class GuestAreaWebConfig implements WebMvcConfigurer {

  private final GuestAreaInterceptor guard;

  GuestAreaWebConfig(GuestAreaInterceptor guard) {
    this.guard = guard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(guard)
        .addPathPatterns("/bff/guest/**", "/bff/temporary-guest/**")
        .excludePathPatterns("/bff/guest/onboarding", "/bff/guest/onboarding/**");
  }
}
