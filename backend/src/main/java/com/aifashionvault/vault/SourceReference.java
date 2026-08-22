package com.aifashionvault.vault;

import java.time.Instant;

import com.aifashionvault.shared.SourceReferenceId;

public record SourceReference(
	SourceReferenceId id,
	String url,
	String retailer,
	String rawMetadata,
	Instant createdAt) {

	public SourceReference {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
