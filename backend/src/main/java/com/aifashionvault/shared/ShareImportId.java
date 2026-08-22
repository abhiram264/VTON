package com.aifashionvault.shared;

import java.util.UUID;

public record ShareImportId(UUID value) {

	public ShareImportId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
