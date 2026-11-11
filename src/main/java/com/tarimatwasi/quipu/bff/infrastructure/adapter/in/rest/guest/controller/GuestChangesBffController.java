package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.guest.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuestChangesBffController {
  private final ChangeStreamWriter streams;

  public GuestChangesBffController(ChangeStreamWriter streams) {
    this.streams = streams;
  }

  @GetMapping("/bff/guest/changes/stream")
  public void stream(JwtAuthenticationToken authentication, HttpServletResponse response)
      throws IOException {
    streams.stream(authentication, false, response);
  }
}
