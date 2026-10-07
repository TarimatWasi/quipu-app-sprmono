package com.tarimatwasi.quipu.shared.adapter.out.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Cloudflare R2 bucket ({@code app.r2.*}). Empty in the local profile, where the connectivity
 * check reports FAIL instead of stopping the startup.
 *
 * @param accountId {@code app.r2.account-id}
 * @param accessKeyId {@code app.r2.access-key-id}
 * @param secretAccessKey {@code app.r2.secret-access-key}
 * @param endpoint {@code app.r2.endpoint}
 * @param bucketName {@code app.r2.bucket-name}
 */
@ConfigurationProperties("app.r2")
@Validated
public record R2Properties(
    String accountId,
    String accessKeyId,
    String secretAccessKey,
    String endpoint,
    String bucketName) {}
