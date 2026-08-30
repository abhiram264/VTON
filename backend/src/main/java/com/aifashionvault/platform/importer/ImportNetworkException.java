package com.aifashionvault.platform.importer;

/**
 * Internal import fetch failure carrying only a safe classification. Messages
 * must never contain URLs, hostnames, keys, or fetched content.
 */
final class ImportNetworkException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final FailureKind kind;

	ImportNetworkException(FailureKind kind, String message) {
		super(message);
		this.kind = kind;
	}

	ImportNetworkException(FailureKind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	FailureKind kind() {
		return kind;
	}
}
