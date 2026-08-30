package com.aifashionvault.importer;

import java.util.Objects;

public final class IdempotencyKeys {

	private IdempotencyKeys() {
	}

	public static void validateFormat(String idempotencyKey) {
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		if (idempotencyKey.length() < ImportResolverLimits.MIN_IDEMPOTENCY_KEY_LENGTH
			|| idempotencyKey.length() > ImportResolverLimits.MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new IllegalArgumentException("idempotencyKey length must be between "
				+ ImportResolverLimits.MIN_IDEMPOTENCY_KEY_LENGTH + " and "
				+ ImportResolverLimits.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		for (int index = 0; index < idempotencyKey.length(); index++) {
			char character = idempotencyKey.charAt(index);
			if (character < 0x20 || character > 0x7E) {
				throw new IllegalArgumentException("idempotencyKey must contain only printable ASCII characters");
			}
		}
	}
}
