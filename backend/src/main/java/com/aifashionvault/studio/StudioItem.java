package com.aifashionvault.studio;

import java.time.Instant;

import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.VaultItemId;

public record StudioItem(
	StudioItemId id,
	StudioSessionId studioSessionId,
	StudioSlot slot,
	VaultItemId vaultItemId,
	int position,
	Instant createdAt) {

	public StudioItem {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (studioSessionId == null) {
			throw new IllegalArgumentException("studioSessionId is required");
		}
		if (slot == null) {
			throw new IllegalArgumentException("slot is required");
		}
		if (vaultItemId == null) {
			throw new IllegalArgumentException("vaultItemId is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
