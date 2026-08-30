package com.aifashionvault.shared;

import java.util.UUID;

public record MediaAssetId(UUID value) {
	public MediaAssetId {
		if (value == null) throw new IllegalArgumentException("value is required");
	}
}
