package com.tarimatwasi.quipu.shared.adapter.out.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Resend account used to send email ({@code app.resend.*}).
 *
 * @param apiKey {@code app.resend.api-key}; empty in the local profile, where Resend rejects the
 *     request and the failure is only logged
 * @param fromEmail {@code app.resend.from-email}: the sender. While it belongs to {@code
 *     resend.dev}, Resend only delivers to the address of the account owner; sending to anyone
 *     needs a verified domain
 */
@ConfigurationProperties("app.resend")
@Validated
public record ResendProperties(String apiKey, String fromEmail) {}
