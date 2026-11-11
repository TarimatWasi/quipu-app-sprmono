package com.tarimatwasi.quipu.bff.infrastructure.adapter.in.rest.common.support;

import com.tarimatwasi.quipu.auth.application.port.in.StreamSessionUseCase;
import com.tarimatwasi.quipu.shared.access.AccessSessions;
import com.tarimatwasi.quipu.shared.access.SessionFingerprint;
import com.tarimatwasi.quipu.shared.masking.MaskSessionSource;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Owns blocking writes on the virtual request thread; never on the mail executor. */
@Component
public class ChangeStreamWriter {
  private static final Logger LOG = LoggerFactory.getLogger(ChangeStreamWriter.class);
  private final ChangeStreamRegistry registry;
  private final StreamSessionUseCase authorization;
  private final MaskSessionSource masks;
  private final Clock clock;

  public ChangeStreamWriter(
      ChangeStreamRegistry registry,
      StreamSessionUseCase authorization,
      MaskSessionSource masks,
      Clock clock) {
    this.registry = registry;
    this.authorization = authorization;
    this.masks = masks;
    this.clock = clock;
  }

  public void stream(
      JwtAuthenticationToken authentication, boolean administrator, HttpServletResponse response)
      throws IOException {
    var opened = clock.instant();
    String reason = "complete";
    try (var listener = reserve(authentication, administrator)) {
      Jwt token = authentication.getToken();
      var identity =
          authorization.authorize(
              Long.valueOf(authentication.getName()),
              SessionFingerprint.of(Objects.requireNonNull(token.getClaimAsString("sid"))),
              ((Number) Objects.requireNonNull(token.getClaim("sv"))).longValue(),
              administrator);
      String ready = listener.activate(identity.guestId());
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("text/event-stream");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader("Cache-Control", "no-store, no-transform");
      response.setHeader("X-Accel-Buffering", "no");
      reason = write(listener, ready, response.getOutputStream());
    } catch (com.tarimatwasi.quipu.auth.application.port.in.NoActiveSessionException
        | org.springframework.security.oauth2.server.resource.InvalidBearerTokenException denied) {
      reason = "authorization-rejected";
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write("{\"code\":\"AUTH_NO_SESSION\",\"message\":\"Tu sesión no es válida o expiró\"}");
    } finally {
      LOG.info(
          "SSE stream closed: {} after {} s",
          reason,
          Duration.between(opened, clock.instant()).toSeconds());
    }
  }

  private ChangeStreamRegistry.Subscription reserve(
      JwtAuthenticationToken authentication, boolean administrator) {
    Jwt token = authentication.getToken();
    String sid = Objects.requireNonNull(token.getClaimAsString(AccessSessions.SESSION_ID_CLAIM));
    Number version = Objects.requireNonNull(token.getClaim("sv"));
    return registry.reserve(
        Long.valueOf(authentication.getName()),
        SessionFingerprint.of(sid),
        version.longValue(),
        administrator,
        masks.current().orElseThrow(),
        Objects.requireNonNull(token.getExpiresAt()));
  }

  private static String write(
      ChangeStreamRegistry.Subscription listener,
      String ready,
      jakarta.servlet.ServletOutputStream output) {
    try {
      output.write(ready.getBytes(StandardCharsets.UTF_8));
      output.flush();
      while (!listener.finished()) {
        String frame = listener.next();
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
      }
      return listener.closeReason();
    } catch (IOException disconnected) {
      return "write-failed";
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "interrupted";
    }
  }
}
