package com.tarimatwasi.quipu.shared.adapter.out.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;

/**
 * The Cloudflare R2 bucket ({@code app.r2.*}). Empty in the local profile, where the connectivity
 * check reports FAIL instead of stopping the startup.
 *
 * @param accountId {@code app.r2.account-id}
 * @param accessKeyId {@code app.r2.access-key-id}
 * @param secretAccessKey {@code app.r2.secret-access-key}
 * @param endpoint {@code app.r2.endpoint}
 * @param bucketName {@code app.r2.bucket-name}
 * @param apiCallTimeout {@code app.r2.api-call-timeout}: the whole call, retries included; below
 *     the lock_timeout of the database (15 s), since some uploads hold row locks (TAR-204)
 * @param apiCallAttemptTimeout {@code app.r2.api-call-attempt-timeout}: each try of the call
 */
@ConfigurationProperties("app.r2")
@Validated
public record R2Properties(
    String accountId,
    String accessKeyId,
    String secretAccessKey,
    String endpoint,
    String bucketName,
    @DefaultValue("10s") Duration apiCallTimeout,
    @DefaultValue("5s") Duration apiCallAttemptTimeout) {

  /** The timeouts of every client that calls R2, so a bucket that hangs never holds a request. */
  public ClientOverrideConfiguration clientOverrides() {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(apiCallTimeout)
        .apiCallAttemptTimeout(apiCallAttemptTimeout)
        .build();
  }

  /** Without the secret: a record prints every component, and this one would reach a log. */
  @Override
  public String toString() {
    return "R2Properties[accountId="
        + accountId
        + ", endpoint="
        + endpoint
        + ", bucketName="
        + bucketName
        + ", accessKeyId=***, secretAccessKey=***]";
  }
}
