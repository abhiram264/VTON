package com.aifashionvault.platform.security;

public record VerifiedExternalIdentity(String issuer, String subject) {

	public VerifiedExternalIdentity {
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalArgumentException("issuer is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
	}
}
