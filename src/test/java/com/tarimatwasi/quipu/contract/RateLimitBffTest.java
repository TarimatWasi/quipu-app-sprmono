package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static com.tarimatwasi.quipu.support.BrowserCsrf.csrf;
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

/** SEC-01 through the real security chain, with a small allowance so the test stays short. */
@SpringBootTest(
    properties = {"app.cors.allowed-origin=http://localhost:3000", "app.rate-limit.per-minute=3"})
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class RateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();

  @Autowired MockMvc mockMvc;

  private int forgot() throws Exception {
    return mockMvc
        .perform(
            post("/bff/auth/forgot-password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.test\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void theFourthSensitiveRequestOfTheMinuteGets429WithRetryAfter() throws Exception {
    for (int i = 0; i < 3; i++) {
      org.assertj.core.api.Assertions.assertThat(forgot()).isEqualTo(202);
    }

    mockMvc
        .perform(
            post("/bff/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentType\":\"DNI\",\"documentNumber\":\"1\",\"password\":\"x\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(openApi().isValid(SPEC));
  }
}
