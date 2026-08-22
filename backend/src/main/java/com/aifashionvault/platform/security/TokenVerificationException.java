package com.aifashionvault.platform.security;

public final class TokenVerificationException extends RuntimeException {

	private final TokenVerificationFailure failure;

	public TokenVerificationException(TokenVerificationFailure failure) {
		super(failure.name());
		this.failure = failure;
	}

	public TokenVerificationFailure failure() {
		return failure;
	}

	@Override
	public String toString() {
		return failure.name();
	}
}
