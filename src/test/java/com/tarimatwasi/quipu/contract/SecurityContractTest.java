package com.tarimatwasi.quipu.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** BE-SPR-SEC-01, SEC-04, SEC-05, SEC-09 and QP-SPRMONO-CSR-01 (ADR-006, TC-08). */
@SpringBootTest
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class SecurityContractTest {

  private static final String ALLOWED_ORIGIN = "http://localhost:4200";

  @Autowired MockMvc mvc;

  @Test
  void health_is_public() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void any_other_path_requires_authentication() throws Exception {
    mvc.perform(get("/api/v1/anything")).andExpect(status().isUnauthorized());
  }

  @Test
  void state_changing_request_with_a_non_json_body_is_rejected_with_415() throws Exception {
    mvc.perform(post("/api/v1/anything").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void state_changing_request_with_a_form_content_type_is_rejected_even_if_empty()
      throws Exception {
    mvc.perform(post("/api/v1/anything").contentType(MediaType.APPLICATION_FORM_URLENCODED))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void json_with_charset_reaches_authorization() throws Exception {
    mvc.perform(
            post("/api/v1/anything").contentType("application/json;charset=UTF-8").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void bodyless_action_requests_reach_authorization() throws Exception {
    mvc.perform(post("/api/v1/anything")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/v1/anything")).andExpect(status().isUnauthorized());
  }

  @Test
  void bodyless_cross_site_request_is_stopped_by_cors() throws Exception {
    mvc.perform(post("/api/v1/anything").header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
  }

  @Test
  void cors_allows_only_the_exact_configured_origin() throws Exception {
    mvc.perform(
            options("/api/v1/anything")
                .header("Origin", ALLOWED_ORIGIN)
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    mvc.perform(
            options("/api/v1/anything")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void security_headers_are_present() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().exists("Content-Security-Policy"))
        .andExpect(header().string("X-Frame-Options", "DENY"));
  }
}
