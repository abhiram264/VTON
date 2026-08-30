package com.aifashionvault.platform.importer;

import com.aifashionvault.importer.ResolutionReason;

/**
 * Retryable infrastructure failure inside a resolver: timeout, DNS, remote 5xx,
 * or object-store unavailability. Carries only opaque reasons — never URLs,
 * keys, HTML, or bytes.
 */
public final class ImporterRetryableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ResolutionReason reason;

	public ImporterRetryableException(ResolutionReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public ResolutionReason reason() {
		return reason;
	}
}
