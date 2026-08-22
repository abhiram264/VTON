package com.aifashionvault.generation;

import java.util.Map;

import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;

public record GenerationJob(GenerationJobId id, UserId userId, StudioSessionId studioSessionId, String status, String requestedMode, ClientRequestId clientRequestId, java.time.Instant createdAt, java.time.Instant completedAt) {

	public GenerationJob {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (status == null || status.isBlank()) {
			throw new IllegalArgumentException("status is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}

	public static GenerationJob rememberIntent(Map<ClientRequestId, GenerationJob> intents, GenerationJob job) {
		if (job == null) {
			throw new IllegalArgumentException("job is required");
		}
		ClientRequestId requestId = job.clientRequestId();
		if (requestId == null) {
			return job;
		}
		if (intents == null) {
			throw new IllegalArgumentException("intents is required");
		}
		GenerationJob existing = intents.putIfAbsent(requestId, job);
		return existing != null ? existing : job;
	}
}
