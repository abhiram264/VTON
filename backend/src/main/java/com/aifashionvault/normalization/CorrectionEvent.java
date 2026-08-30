package com.aifashionvault.normalization;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public record CorrectionEvent(
		UUID id,
		UserId userId,
		VaultItemId vaultItemId,
		ProductCategory predictedCategory,
		ProductCategory correctedCategory,
		double confidence,
		Instant createdAt) {

	public CorrectionEvent {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(vaultItemId, "vaultItemId");
		Objects.requireNonNull(predictedCategory, "predictedCategory");
		Objects.requireNonNull(correctedCategory, "correctedCategory");
		Objects.requireNonNull(createdAt, "createdAt");
		Confidence.requireValid(confidence);
		if (confidence >= 0.70) {
			throw new IllegalArgumentException("correction requires confidence below 0.70");
		}
		if (correctedCategory == ProductCategory.UNKNOWN) {
			throw new IllegalArgumentException("corrected category must resolve uncertainty");
		}
		if (correctedCategory == predictedCategory) {
			throw new IllegalArgumentException("corrected category must differ from predicted category");
		}
	}
}
