package com.aifashionvault.importer;

import java.net.URI;
import java.util.Objects;

import com.aifashionvault.shared.StorageKey;

public record ResolutionResult(
	ResolutionStatus status,
	StorageKey storageKey,
	URI canonicalSourceUrl,
	String title,
	String siteName,
	ResolutionReason reason) {

	public ResolutionResult {
		Objects.requireNonNull(status, "status");
		switch (status) {
			case RESOLVED -> Objects.requireNonNull(storageKey, "storageKey");
			case NEEDS_IMAGE, RETRYABLE_FAILURE, TERMINAL_FAILURE -> Objects.requireNonNull(reason, "reason");
			case UNSUPPORTED -> {
			}
		}
	}
}
