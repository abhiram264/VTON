package com.aifashionvault.importer;

import java.util.Objects;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UserId;

public record ResolutionContext(
	UserId ownerId,
	ShareImportId importId,
	String attemptToken,
	ImportMediaAccess mediaAccess) {

	public ResolutionContext {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(importId, "importId");
		Objects.requireNonNull(attemptToken, "attemptToken");
		Objects.requireNonNull(mediaAccess, "mediaAccess");
		if (attemptToken.isBlank()) {
			throw new IllegalArgumentException("attemptToken is required");
		}
	}
}
