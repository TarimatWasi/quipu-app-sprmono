package com.tarimatwasi.quipu.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tarimatwasi.quipu.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TAR-75: a preflight from a legitimate Vercel host (the primary origin, the extra one and a host
 * matched by the pattern) is answered with credentials allowed, and lookalikes get 403, through the
 * real security filter chain.
 */
@SpringBootTest(
    properties = {
      "app.cors.allowed-origin=https://quipu-app-angweb-dev.vercel.app",
      "app.cors.allowed-origins[0]=https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app",
      "app.cors.allowed-origin-patterns[0]=https://quipu-app-angweb-*-shizukajikus-projects.vercel.app"
    })
@AutoConfigureMockMvc
@ImportTestcontainers(PostgresContainers.class)
class CorsPreflightContractTest {

  private static final String LOGIN = "/bff/auth/login";

  @Autowired MockMvc mvc;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://quipu-app-angweb-dev.vercel.app",
        "https://quipu-app-angweb-dev-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-dev-git-feat-tar-93ae08-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app"
      })
  void aLegitimateOriginPassesThePreflightWithCredentials(String origin) throws Exception {
    var response =
        mvc.perform(
                options(LOGIN)
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", origin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andReturn()
            .getResponse();

    assertThat(response.getHeader("Access-Control-Allow-Headers"))
        .containsIgnoringCase("x-xsrf-token");
    // The answer depends on the Origin, so caches must key on it.
    assertThat(String.join(",", response.getHeaders("Vary"))).contains("Origin");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://quipu-app-angweb-dev.vercel.app",
        "http://quipu-app-angweb-9n80jc9hm-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-x.evil.com-shizukajikus-projects.vercel.app",
        "https://quipu-app-angweb-9n80jc9hm-otherteam-projects.vercel.app",
        "https://quipu-app-angweb-dev.vercel.app.evil.com"
      })
  void aLookalikeOrAnHttpVariantIsRejected(String origin) throws Exception {
    mvc.perform(
            options(LOGIN)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void theCredentialsHeaderIsNeverSentToAForeignOrigin() throws Exception {
    mvc.perform(
            options(LOGIN)
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
  }
}
