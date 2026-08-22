package com.aifashionvault.importer;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

public record ShareImport(ShareImportId id, UserId userId, String sourceKind, String sourceUrl, StorageKey storageKey, String resolverState, java.time.Instant createdAt, java.time.Instant updatedAt) {

	public ShareImport {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (sourceKind == null || sourceKind.isBlank()) {
			throw new IllegalArgumentException("sourceKind is required");
		}
		if (resolverState == null || resolverState.isBlank()) {
			throw new IllegalArgumentException("resolverState is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
	}
}
