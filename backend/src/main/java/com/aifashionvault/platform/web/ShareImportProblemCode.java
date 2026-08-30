package com.aifashionvault.platform.web;

/**
 * Opaque problem codes for the share-import API. Responses carry the code only;
 * never a URL, URI, object key, HTML, or media data (ADR-015).
 */
public enum ShareImportProblemCode {
	IDEMPOTENCY_KEY_REQUIRED,
	IDEMPOTENCY_KEY_REUSED,
	IMPORT_NOT_FOUND,
	IMPORT_STATE_CONFLICT,
	UPLOAD_NOT_CLAIMABLE,
	INVALID_SHARE_IMPORT,
	DEPENDENCY_UNAVAILABLE,
	RESOLUTION_TIMEOUT
}
