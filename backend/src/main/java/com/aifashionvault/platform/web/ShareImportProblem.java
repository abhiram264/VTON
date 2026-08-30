package com.aifashionvault.platform.web;

import org.springframework.http.HttpStatus;

/**
 * Share-import API problem. Carries only the opaque code — never a URL, URI,
 * object key, HTML, or media data (ADR-015).
 */
public final class ShareImportProblem extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;
	private final ShareImportProblemCode code;

	private ShareImportProblem(HttpStatus status, ShareImportProblemCode code) {
		super(code.name());
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public ShareImportProblemCode code() {
		return code;
	}

	static ShareImportProblem invalid() {
		return new ShareImportProblem(HttpStatus.BAD_REQUEST, ShareImportProblemCode.INVALID_SHARE_IMPORT);
	}

	static ShareImportProblem keyRequired() {
		return new ShareImportProblem(HttpStatus.BAD_REQUEST, ShareImportProblemCode.IDEMPOTENCY_KEY_REQUIRED);
	}

	static ShareImportProblem keyReused() {
		return new ShareImportProblem(HttpStatus.CONFLICT, ShareImportProblemCode.IDEMPOTENCY_KEY_REUSED);
	}

	static ShareImportProblem notFound() {
		return new ShareImportProblem(HttpStatus.NOT_FOUND, ShareImportProblemCode.IMPORT_NOT_FOUND);
	}

	static ShareImportProblem stateConflict() {
		return new ShareImportProblem(HttpStatus.CONFLICT, ShareImportProblemCode.IMPORT_STATE_CONFLICT);
	}

	static ShareImportProblem uploadNotClaimable() {
		return new ShareImportProblem(HttpStatus.CONFLICT, ShareImportProblemCode.UPLOAD_NOT_CLAIMABLE);
	}

	static ShareImportProblem dependencyUnavailable() {
		return new ShareImportProblem(HttpStatus.SERVICE_UNAVAILABLE, ShareImportProblemCode.DEPENDENCY_UNAVAILABLE);
	}

	static ShareImportProblem resolutionTimeout() {
		return new ShareImportProblem(HttpStatus.GATEWAY_TIMEOUT, ShareImportProblemCode.RESOLUTION_TIMEOUT);
	}
}
