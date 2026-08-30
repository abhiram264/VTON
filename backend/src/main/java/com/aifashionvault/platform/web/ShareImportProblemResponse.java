package com.aifashionvault.platform.web;

public record ShareImportProblemResponse(String code) {

	public ShareImportProblemResponse {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code is required");
		}
	}
}
