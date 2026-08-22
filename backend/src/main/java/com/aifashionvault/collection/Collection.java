package com.aifashionvault.collection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public record Collection(
	CollectionId id,
	UserId userId,
	String name,
	Instant createdAt,
	Instant updatedAt,
	List<CollectionItem> items) {

	public Collection {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
		if (items == null) {
			throw new IllegalArgumentException("items is required");
		}
		for (CollectionItem item : items) {
			if (item == null) {
				throw new IllegalArgumentException("item is required");
			}
		}
		items = List.copyOf(items);
	}

	public Collection addItem(VaultItemId vaultItemId, Instant createdAt) {
		if (vaultItemId == null) {
			throw new IllegalArgumentException("vaultItemId is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		for (CollectionItem item : items) {
			if (item.vaultItemId().equals(vaultItemId)) {
				return this;
			}
		}
		List<CollectionItem> next = new ArrayList<>(items);
		next.add(new CollectionItem(id, vaultItemId, createdAt));
		return new Collection(id, userId, name, this.createdAt, updatedAt, next);
	}

	public Collection removeItem(VaultItemId vaultItemId) {
		if (vaultItemId == null) {
			throw new IllegalArgumentException("vaultItemId is required");
		}
		List<CollectionItem> next = new ArrayList<>();
		boolean removed = false;
		for (CollectionItem item : items) {
			if (item.vaultItemId().equals(vaultItemId)) {
				removed = true;
				continue;
			}
			next.add(item);
		}
		if (!removed) {
			return this;
		}
		return new Collection(id, userId, name, createdAt, updatedAt, next);
	}
}
