package com.aifashionvault.normalization;

import java.util.UUID;

public final class CorrectionEventIdentityConflictException extends IllegalStateException {

	public CorrectionEventIdentityConflictException(UUID id) {
		super("correction event identity already has different payload: " + id);
	}
}
