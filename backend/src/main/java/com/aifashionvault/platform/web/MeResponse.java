package com.aifashionvault.platform.web;

public record MeResponse(String id) {

	public MeResponse {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id is required");
		}
	}
}
