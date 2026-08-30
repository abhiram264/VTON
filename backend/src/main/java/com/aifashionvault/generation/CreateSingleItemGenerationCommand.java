package com.aifashionvault.generation;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public record CreateSingleItemGenerationCommand(UserId ownerId, UploadId productUploadId, String idempotencyKey) {
	public CreateSingleItemGenerationCommand {
		if (ownerId == null || productUploadId == null) throw new IllegalArgumentException("ownerId and productUploadId are required");
		if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
	}
}
