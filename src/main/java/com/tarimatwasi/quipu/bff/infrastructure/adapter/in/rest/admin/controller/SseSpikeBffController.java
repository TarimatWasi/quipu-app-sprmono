package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.admin.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TAR-365 spike, temporary: measures whether a long {@code text/event-stream} survives the way from
 * the browser to the backend (Vercel, Cloudflare, Render). It is not in the contract and changes no
 * data. Removed by the adoption PR, or as soon as the measurement is negative.
 *
 * <p>The stream is written on the thread of the request itself (a virtual thread, since {@code
 * spring.threads.virtual.enabled}): no executor and no scheduled task (BE-SPR-CON-01), and no slot
 * of the shared {@code applicationTaskExecutor}. The session is checked once, when the stream
 * opens, and the stream ends when the access token expires, so a client must refresh and reopen.
 *
 * <p>Events: {@code hello} when it opens, {@code tick} every {@code app.spike.sse.interval} with
 * the time of the server, {@code bye} with the reason when it ends. Each close is logged with its
 * reason and its length: {@code client-gone} before {@code closesAt} means that something on the
 * way cut the stream.
 */
@RestController
@ConditionalOnBooleanProperty("app.spike.sse.enabled")
public class SseSpikeBffController {

  private static final Logger LOGGER = LoggerFactory.getLogger(SseSpikeBffController.class);

  private final SseSpikeProperties properties;
  private final Clock clock;

  public SseSpikeBffController(SseSpikeProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /** The stream of one ADMIN session; SecurityConfig reserves /bff/admin/** to ADMIN. */
  @GetMapping("/bff/admin/spike/stream")
  public void stream(@AuthenticationPrincipal Jwt token, HttpServletResponse response)
      throws IOException {
    Instant opened = clock.instant();
    Instant byDuration = opened.plus(properties.maxDuration());
    Instant expiresAt = token.getExpiresAt();
    boolean byToken = expiresAt != null && expiresAt.isBefore(byDuration);
    Instant closesAt = byToken ? expiresAt : byDuration;

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("text/event-stream");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Cache-Control", "no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    ServletOutputStream out = response.getOutputStream();

    long ticks = 0;
    String reason;
    try {
      send(out, "hello", "{\"openedAt\":\"" + opened + "\",\"closesAt\":\"" + closesAt + "\"}");
      while (true) {
        Instant now = clock.instant();
        if (!now.isBefore(closesAt)) {
          reason = byToken ? "token-expired" : "max-duration";
          send(out, "bye", "{\"reason\":\"" + reason + "\"}");
          break;
        }
        Duration left = Duration.between(now, closesAt);
        Thread.sleep(left.compareTo(properties.interval()) < 0 ? left : properties.interval());
        Instant at = clock.instant();
        if (at.isBefore(closesAt)) {
          ticks++;
          send(out, "tick", "{\"seq\":" + ticks + ",\"at\":\"" + at + "\"}");
        }
      }
    } catch (IOException clientGone) {
      reason = "client-gone";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      reason = "interrupted";
    }
    LOGGER.info(
        "SSE spike stream closed: {} after {} s, {} ticks (planned close after {} s)",
        reason,
        Duration.between(opened, clock.instant()).toSeconds(),
        ticks,
        Duration.between(opened, closesAt).toSeconds());
  }

  private static void send(ServletOutputStream out, String event, String data) throws IOException {
    out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }
}
