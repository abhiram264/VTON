package com.aifashionvault.vault;

import java.time.Instant;

import com.aifashionvault.shared.SourceReferenceId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public record VaultItem(
	VaultItemId id,
	UserId userId,
	StorageKey storageKey,
	SourceReferenceId sourceReferenceId,
	String name,
	String brand,
	String category,
	String subcategory,
	String color,
	String pattern,
	String attributes,
	Instant createdAt) {

	public VaultItem {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (storageKey == null) {
			throw new IllegalArgumentException("storageKey is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
