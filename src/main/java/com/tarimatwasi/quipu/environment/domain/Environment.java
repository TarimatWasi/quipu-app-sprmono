package com.tarimatwasi.quipu.environment.domain;

import java.util.UUID;

public record Environment(UUID id, String code, EnvironmentType type, EnvironmentStatus status) {}
