package com.aifashionvault.user;

import java.time.Instant;

import com.aifashionvault.shared.IdentityMappingId;
import com.aifashionvault.shared.UserId;

public record IdentityMapping(IdentityMappingId id, UserId userId, String issuer, String subject, Instant createdAt) {

	public IdentityMapping {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalArgumentException("issuer is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}

	public boolean sameExternalIdentity(IdentityMapping other) {
		return other != null
			&& issuer.equals(other.issuer)
			&& subject.equals(other.subject);
	}
}
