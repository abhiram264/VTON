package com.aifashionvault.platform.web;

public record GenerationProblemResponse(String code) {

	public GenerationProblemResponse {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code is required");
		}
	}
}
