package com.tarimatwasi.quipu.auth.application.service;

import com.tarimatwasi.quipu.auth.application.port.out.UserRepositoryPort;
import org.springframework.stereotype.Component;

/** The accounts and the security facts their changes state (BE-SPR-DI-05). */
@Component
record AccountAccess(UserRepositoryPort users, SecurityFacts facts) {}
