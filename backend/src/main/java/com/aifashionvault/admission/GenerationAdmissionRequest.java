package com.aifashionvault.admission;

import java.time.Instant;
import java.util.Objects;
import com.aifashionvault.shared.UserId;

public record GenerationAdmissionRequest(UserId userId, String deviceId, String ipAddress,
	GenerationRiskTier riskTier, Instant requestedAt) {
	public GenerationAdmissionRequest {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(riskTier, "riskTier");
		Objects.requireNonNull(requestedAt, "requestedAt");
	}
}
