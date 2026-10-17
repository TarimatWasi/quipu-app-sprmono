package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.support.PostgresContainers;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** TAR-36, SEC-01: the public activation endpoint has the same address limit as the login. */
@SpringBootTest(
    properties = {"app.cors.allowed-origin=http://localhost:3000", "app.rate-limit.per-minute=2"})
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class ActivateRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();

  @Autowired MockMvc mockMvc;

  private ResultActions activate() throws Exception {
    return mockMvc.perform(
        post("/bff/auth/activate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"guess\",\"newPassword\":\"MiClave-2026\"}"));
  }

  @Test
  void theThirdActivationOfTheMinuteGets429() throws Exception {
    activate().andExpect(status().isBadRequest());
    activate().andExpect(status().isBadRequest());

    activate()
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(openApi().isValid(SPEC));
  }
}
