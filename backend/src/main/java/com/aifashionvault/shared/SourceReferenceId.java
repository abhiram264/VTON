package com.aifashionvault.shared;

import java.util.UUID;

public record SourceReferenceId(UUID value) {

	public SourceReferenceId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
