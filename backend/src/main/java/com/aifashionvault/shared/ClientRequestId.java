package com.aifashionvault.shared;

public record ClientRequestId(String value) {

	public ClientRequestId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
