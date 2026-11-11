package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support.ChangeStreamWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminChangesBffController {
  private final ChangeStreamWriter streams;

  public AdminChangesBffController(ChangeStreamWriter streams) {
    this.streams = streams;
  }

  @GetMapping("/bff/admin/changes/stream")
  public void stream(JwtAuthenticationToken authentication, HttpServletResponse response)
      throws IOException {
    streams.stream(authentication, true, response);
  }
}
