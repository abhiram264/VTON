package com.aifashionvault.collection;

import java.time.Instant;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.VaultItemId;

public record CollectionItem(CollectionId collectionId, VaultItemId vaultItemId, Instant createdAt) {

	public CollectionItem {
		if (collectionId == null) {
			throw new IllegalArgumentException("collectionId is required");
		}
		if (vaultItemId == null) {
			throw new IllegalArgumentException("vaultItemId is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
