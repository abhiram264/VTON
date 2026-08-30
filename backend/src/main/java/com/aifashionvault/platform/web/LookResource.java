package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import com.aifashionvault.shared.QualityDecision;

public record LookResource(
	UUID id,
	UUID avatarId,
	UUID generationId,
	UUID studioSessionId,
	JsonNode renderPlan,
	String provider,
	String providerModel,
	QualityDecision qualityStatus,
	Instant createdAt,
	List<LookItemResource> items,
	ReadCapability image) {

	public LookResource {
		items = List.copyOf(items);
	}
}
