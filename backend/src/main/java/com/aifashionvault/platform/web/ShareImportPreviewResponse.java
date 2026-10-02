package com.aifashionvault.platform.web;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Owner-scoped preview of a resolved share import, returned before the user
 * saves it. {@code previewUrl} is a short-lived signed read capability — never
 * the object key or a permanent URL — and is re-issuable by calling the route
 * again. {@code sourceTitle}/{@code sourceSiteName} are the safe display
 * metadata the resolver already extracted; no source query string, object key
 * or media bytes are exposed (ADR-015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShareImportPreviewResponse(
	String importId,
	String previewUrl,
	Instant previewExpiresAt,
	String sourceTitle,
	String sourceSiteName
) {

	public ShareImportPreviewResponse {
		if (importId == null || importId.isBlank()) {
			throw new IllegalArgumentException("importId is required");
		}
		if (previewUrl == null || previewUrl.isBlank()) {
			throw new IllegalArgumentException("previewUrl is required");
		}
		if (previewExpiresAt == null) {
			throw new IllegalArgumentException("previewExpiresAt is required");
		}
	}
}
