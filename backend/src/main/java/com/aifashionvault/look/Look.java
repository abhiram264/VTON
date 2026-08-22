package com.aifashionvault.look;

import java.time.Instant;
import java.util.List;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;

public record Look(
	LookId id,
	UserId userId,
	AvatarProfileId avatarProfileId,
	GenerationJobId generationJobId,
	StudioSessionId studioSessionId,
	StorageKey storageKey,
	String renderPlan,
	String provider,
	String providerModel,
	QualityDecision qualityStatus,
	Instant createdAt,
	List<LookItem> items) {

	public Look {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (storageKey == null) {
			throw new IllegalArgumentException("storageKey is required");
		}
		if (qualityStatus != QualityDecision.PASS) {
			throw new IllegalArgumentException("qualityStatus must be PASS");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (items == null) {
			throw new IllegalArgumentException("items is required");
		}
		for (LookItem item : items) {
			if (item == null) {
				throw new IllegalArgumentException("item is required");
			}
		}
		items = List.copyOf(items);
	}
}
