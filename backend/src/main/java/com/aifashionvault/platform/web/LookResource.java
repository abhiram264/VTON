package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.UUID;

public record LookResource(UUID id, UUID generationId, Instant createdAt, ReadCapability image) {}
