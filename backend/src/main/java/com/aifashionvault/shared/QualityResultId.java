package com.aifashionvault.shared;

import java.util.UUID;

public record QualityResultId(UUID value) {

	public QualityResultId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
