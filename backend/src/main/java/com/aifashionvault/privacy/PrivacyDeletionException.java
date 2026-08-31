package com.aifashionvault.privacy;

public final class PrivacyDeletionException extends RuntimeException {

	private final String safeCode;

	public PrivacyDeletionException(String safeCode, Throwable cause) {
		super(safeCode, cause);
		if (safeCode == null || safeCode.isBlank()) {
			throw new IllegalArgumentException("safeCode is required");
		}
		this.safeCode = safeCode;
	}

	public String safeCode() {
		return safeCode;
	}
}
