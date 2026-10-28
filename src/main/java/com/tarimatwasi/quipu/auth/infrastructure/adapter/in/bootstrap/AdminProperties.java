package com.tarimatwasi.quipu.auth.infrastructure.adapter.in.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Identity of the first ADMIN (DNI) ({@code app.admin.*}). There is no password: the person chooses
 * it with the link sent to {@code email} (ADR-F2).
 *
 * @param documentNumber {@code app.admin.document-number}
 * @param email {@code app.admin.email}: where the link to choose the password is sent
 * @param required {@code app.admin.required}: the strict profiles refuse to start while there is no
 *     active ADMIN and nothing says how to create one, because nobody could use the system
 */
@ConfigurationProperties("app.admin")
@Validated
public record AdminProperties(
    @DefaultValue("") String documentNumber,
    @DefaultValue("") String email,
    @DefaultValue("false") boolean required) {}
