package com.aifashionvault.privacy;

import java.util.Objects;

/**
 * Raised when an operation needs an active consent the owner has not granted (or has
 * withdrawn). Carries only the purpose, never media, identifiers or credentials.
 */
public final class ConsentRequiredException extends RuntimeException {

	private final transient ConsentPurpose purpose;

	public ConsentRequiredException(ConsentPurpose purpose) {
		super("consent required: " + Objects.requireNonNull(purpose, "purpose is required").wireName());
		this.purpose = purpose;
	}

	public ConsentPurpose purpose() {
		return purpose;
	}
}
