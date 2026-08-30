package com.aifashionvault.importer;

import java.util.Objects;
import java.util.regex.Pattern;

public record SourceResolverRegistration(
	String id,
	int precedence,
	SourceResolverFactory factory) {

	private static final Pattern VALID_ID = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

	public SourceResolverRegistration {
		if (id == null || !VALID_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("id must match [A-Z][A-Z0-9_]{0,63}");
		}
		if (precedence < SourceResolverPrecedence.SHARED_IMAGE
				|| precedence > SourceResolverPrecedence.GENERIC_URL) {
			throw new IllegalArgumentException("precedence must be between 100 and 1000");
		}
		Objects.requireNonNull(factory, "factory");
	}
}
