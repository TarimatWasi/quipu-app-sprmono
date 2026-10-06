package com.tarimatwasi.quipu.ambiente.domain;

import java.util.UUID;

public record Environment(UUID id, String code, EnvironmentType type, EnvironmentStatus status) {}
