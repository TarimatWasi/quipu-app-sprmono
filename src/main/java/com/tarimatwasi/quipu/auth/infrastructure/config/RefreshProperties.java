package com.tarimatwasi.quipu.auth.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * TAR-228: the lifetime of the sessions renewed by refresh tokens ({@code app.refresh.*}); the
 * access token keeps its own in {@code app.jwt.expiration}.
 *
 * @param idle {@code app.refresh.idle}: unused this long, a refresh token stops renewing (7 days)
 * @param absolute {@code app.refresh.absolute}: no session outlives this from its login (30 days)
 * @param reuseGrace {@code app.refresh.reuse-grace}: a token rotated less than this ago is a race
 *     of tabs, not a reuse (30 seconds)
 */
@ConfigurationProperties("app.refresh")
@Validated
public record RefreshProperties(
    @DefaultValue("7d") Duration idle,
    @DefaultValue("30d") Duration absolute,
    @DefaultValue("30s") Duration reuseGrace) {}
