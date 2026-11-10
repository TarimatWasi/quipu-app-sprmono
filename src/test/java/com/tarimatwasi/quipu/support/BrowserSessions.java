package com.tarimatwasi.quipu.support;

import static com.tarimatwasi.quipu.support.BrowserCsrf.postWithCsrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.MockMvc;

/** TAR-228: what the browser does when its access token expires. */
public final class BrowserSessions {

  private BrowserSessions() {}

  /** Renews the session with its refresh token and returns the new access token cookie. */
  public static Cookie renewed(MockMvc mockMvc, @Nullable Cookie refreshToken) throws Exception {
    return Objects.requireNonNull(
        mockMvc
            .perform(postWithCsrf("/bff/auth/refresh").cookie(Objects.requireNonNull(refreshToken)))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse()
            .getCookie("sessionToken"));
  }
}
