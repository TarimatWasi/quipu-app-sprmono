package com.tarimatwasi.quipu.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * TAR-124: the login also counts attempts per account, so the limit does not depend on the address
 * the hosting layers show. The address limit is left high here to see only the account one.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=http://localhost:3000",
      "app.rate-limit.per-minute=1000",
      "app.rate-limit.per-account-per-minute=2"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class AccountRateLimitBffTest {

  private static final String SPEC =
      Path.of("target/contract/bff.yaml").toAbsolutePath().toString();

  @Autowired MockMvc mockMvc;

  private int login(String documentNumber) throws Exception {
    return mockMvc
        .perform(
            post("/bff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\""
                        + documentNumber
                        + "\",\"password\":\"Equivocada-1\"}"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void theThirdAttemptOnOneAccountGets429WithRetryAfterWhateverTheAddress() throws Exception {
    assertThat(login("40000001")).isEqualTo(401);
    assertThat(login("40000001")).isEqualTo(401);

    mockMvc
        .perform(
            post("/bff/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113.50")
                .content(
                    "{\"documentType\":\"DNI\",\"documentNumber\":\"40000001\","
                        + "\"password\":\"Equivocada-1\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(openApi().isValid(SPEC));
  }

  @Test
  void anotherAccountKeepsItsOwnAllowance() throws Exception {
    assertThat(login("40000002")).isEqualTo(401);
    assertThat(login("40000002")).isEqualTo(401);
    assertThat(login("40000002")).isEqualTo(429);

    assertThat(login("40000003")).isEqualTo(401);
  }
}
