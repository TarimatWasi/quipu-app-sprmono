package com.tarimatwasi.quipu.auth.infrastructure.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TAR-144, TAR-9: the secrets bind from {@code app.edge.secrets} and, with them, the gate is the
 * first filter of the real chain: a direct request to /bff is refused (also a CORS preflight), the
 * health check stays open and a request with the secret reaches the endpoint.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.edge.secrets=0123456789abcdef0123456789abcdef"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class EdgePropertiesTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Autowired MockMvc mockMvc;

  @Test
  void aDirectRequestToBffIsRefused() throws Exception {
    mockMvc
        .perform(get("/bff/auth/csrf"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  @Test
  void aPreflightWithoutTheSecretGetsNoCorsAnswer() throws Exception {
    mockMvc
        .perform(
            options("/bff/auth/login")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void theRequestThatVercelForwardsReachesTheEndpoint() throws Exception {
    mockMvc
        .perform(
            get("/bff/auth/csrf")
                .header(EdgeGateFilter.SECRET_HEADER, SECRET)
                .header(EdgeGateFilter.VERCEL_ADDRESS_HEADER, "203.0.113.7"))
        .andExpect(status().isNoContent());
  }

  @Test
  void theHealthCheckIsOpenWithoutTheSecret() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
