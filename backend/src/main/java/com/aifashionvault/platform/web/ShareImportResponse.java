package com.aifashionvault.platform.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Frozen response union for the share-import API. Absent fields are omitted;
 * the body never carries a source URL, object key, or media data (ADR-015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareImportResponse(String importId, String status, String reason, List<String> recoveryActions) {

	public ShareImportResponse {
		if (importId == null || importId.isBlank()) {
			throw new IllegalArgumentException("importId is required");
		}
		if (status == null || status.isBlank()) {
			throw new IllegalArgumentException("status is required");
		}
	}

	public static ShareImportResponse resolving(String importId) {
		return new ShareImportResponse(importId, "RESOLVING", null, null);
	}

	public static ShareImportResponse ready(String importId) {
		return new ShareImportResponse(importId, "READY", null, null);
	}

	public static ShareImportResponse needsImage(String importId) {
		return new ShareImportResponse(importId, "NEEDS_IMAGE", "URL_RESOLUTION_FAILED",
			List.of("CHOOSE_SCREENSHOT", "UPLOAD_IMAGE"));
	}

	public static ShareImportResponse failed(String importId, String status, String reason) {
		return new ShareImportResponse(importId, status, reason, null);
	}
}
