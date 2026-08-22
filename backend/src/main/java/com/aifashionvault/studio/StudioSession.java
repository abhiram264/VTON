package com.aifashionvault.studio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;

public record StudioSession(
	StudioSessionId id,
	UserId userId,
	AvatarProfileId avatarProfileId,
	Instant createdAt,
	Instant updatedAt,
	List<StudioItem> items) {

	public StudioSession {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
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
		for (StudioItem item : items) {
			if (item == null) {
				throw new IllegalArgumentException("item is required");
			}
		}
		items = List.copyOf(items);
		validateItems(id, items);
	}

	public StudioSession addItem(StudioItem item) {
		if (item == null) {
			throw new IllegalArgumentException("item is required");
		}
		List<StudioItem> next = new ArrayList<>(items);
		next.add(item);
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, next);
	}

	public StudioSession removeItem(StudioItemId itemId) {
		if (itemId == null) {
			throw new IllegalArgumentException("itemId is required");
		}
		List<StudioItem> next = new ArrayList<>();
		boolean found = false;
		for (StudioItem item : items) {
			if (item.id().equals(itemId)) {
				found = true;
				continue;
			}
			next.add(item);
		}
		if (!found) {
			throw new IllegalArgumentException("item is not in session");
		}
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, next);
	}

	public StudioSession replaceItem(StudioItem item) {
		if (item == null) {
			throw new IllegalArgumentException("item is required");
		}
		int index = indexToReplace(item);
		if (index < 0) {
			throw new IllegalArgumentException("item is not in session");
		}
		List<StudioItem> next = new ArrayList<>(items);
		next.set(index, item);
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, next);
	}

	public StudioSession clearSlot(StudioSlot slot) {
		if (slot == null) {
			throw new IllegalArgumentException("slot is required");
		}
		List<StudioItem> next = new ArrayList<>();
		for (StudioItem item : items) {
			if (item.slot() != slot) {
				next.add(item);
			}
		}
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, next);
	}

	public StudioSession clearOutfit() {
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, List.of());
	}

	public StudioSession setAvatar(AvatarProfileId avatarProfileId) {
		return new StudioSession(id, userId, avatarProfileId, createdAt, updatedAt, items);
	}

	private int indexToReplace(StudioItem item) {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).id().equals(item.id())) {
				return i;
			}
		}
		if (!item.slot().allowsMultiple()) {
			for (int i = 0; i < items.size(); i++) {
				if (items.get(i).slot() == item.slot()) {
					return i;
				}
			}
		}
		return -1;
	}

	private static void validateItems(StudioSessionId sessionId, List<StudioItem> items) {
		Set<StudioItemId> ids = new HashSet<>();
		for (StudioItem item : items) {
			if (!sessionId.equals(item.studioSessionId())) {
				throw new IllegalArgumentException("studioSessionId must match session");
			}
			if (!ids.add(item.id())) {
				throw new IllegalArgumentException("item id must be unique");
			}
		}
		for (int i = 0; i < items.size(); i++) {
			StudioItem left = items.get(i);
			for (int j = i + 1; j < items.size(); j++) {
				StudioItem right = items.get(j);
				if (left.slot().conflictsWith(right.slot())) {
					throw new IllegalArgumentException("slot conflicts with existing item");
				}
				if (left.slot() == right.slot() && !left.slot().allowsMultiple()) {
					throw new IllegalArgumentException("slot does not allow multiple items");
				}
				if (left.slot() == right.slot() && left.position() == right.position()) {
					throw new IllegalArgumentException("slot position is already assigned");
				}
			}
		}
	}
}
