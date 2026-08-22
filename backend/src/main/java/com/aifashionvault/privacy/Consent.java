package com.aifashionvault.privacy;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

public record Consent(ConsentId id, UserId userId, String purpose, String version, java.time.Instant grantedAt, java.time.Instant revokedAt) {

	public Consent {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (purpose == null || purpose.isBlank()) {
			throw new IllegalArgumentException("purpose is required");
		}
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version is required");
		}
		if (grantedAt == null) {
			throw new IllegalArgumentException("grantedAt is required");
		}
	}
}
