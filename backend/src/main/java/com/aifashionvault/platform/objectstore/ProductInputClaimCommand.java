package com.aifashionvault.platform.objectstore;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.MediaAssetId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public record ProductInputClaimCommand(UserId ownerId, UploadId productUploadId, GenerationJobId generationId,
	MediaAssetId destinationMediaId) {
	public ProductInputClaimCommand {
		if (ownerId == null || productUploadId == null || generationId == null || destinationMediaId == null)
			throw new IllegalArgumentException("product input claim identities are required");
	}
}
