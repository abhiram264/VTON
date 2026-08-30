package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.UUID;
import com.aifashionvault.generation.CoarseGenerationPhase;
import com.aifashionvault.generation.GenerationStatus;

public record GenerationResource(UUID id, GenerationStatus status, CoarseGenerationPhase phase, int statusVersion,
	Instant createdAt, Instant updatedAt, UUID lookId, String failureCode) {}
