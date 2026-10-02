package com.aifashionvault.privacy;

import java.util.Locale;
import java.util.Optional;

/**
 * The bounded set of processing purposes a consumer can grant or withdraw.
 *
 * <p>A purpose is recorded as its stable wire name, so the persisted consent row
 * survives renames of the enum constant. Anything outside this set is refused at
 * the API boundary rather than stored as free text: an unknown purpose would be a
 * consent the product can never check.</p>
 */
public enum ConsentPurpose {
	/** Body-image (avatar photo) processing inside the product. */
	BODY_IMAGE_PROCESSING("body_image_processing"),
	/** Handing the body image to a third-party AI provider for generation. */
	THIRD_PARTY_AI_TRANSFER("third_party_ai_transfer"),
	/** Keeping the body image and generated results for the stated retention window. */
	RETENTION("retention");

	private final String wireName;

	ConsentPurpose(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static Optional<ConsentPurpose> fromWire(String value) {
		if (value == null) {
			return Optional.empty();
		}
		String normalized = value.strip().toLowerCase(Locale.ROOT);
		for (ConsentPurpose purpose : values()) {
			if (purpose.wireName.equals(normalized)) {
				return Optional.of(purpose);
			}
		}
		return Optional.empty();
	}
}
