package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.aifashionvault.generation.CoarseGenerationPhase;
import com.aifashionvault.generation.GenerationStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationResponse(
	UUID id,
	GenerationStatus status,
	CoarseGenerationPhase phase,
	int statusVersion,
	Instant createdAt,
	Instant updatedAt,
	String lookId,
	String failureCode) {

	public GenerationResponse {
		if (id == null || status == null || phase == null || createdAt == null || updatedAt == null) {
			throw new IllegalArgumentException("generation resource fields are required");
		}
	}
}
