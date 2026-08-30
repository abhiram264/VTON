package com.aifashionvault.generation;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.MediaAssetId;
import com.aifashionvault.shared.UserId;

public record FrozenSingleItemIntent(GenerationJobId generationId, UserId ownerId, AvatarImageId avatarImageId,
	MediaAssetId productMediaId, int renderStepOrdinal, int renderStepCount) {
	public FrozenSingleItemIntent {
		if (generationId == null || ownerId == null || avatarImageId == null || productMediaId == null)
			throw new IllegalArgumentException("intent identities are required");
		if (renderStepOrdinal != 1 || renderStepCount != 1)
			throw new IllegalArgumentException("single-item intent requires ordinal/count 1");
	}
}
