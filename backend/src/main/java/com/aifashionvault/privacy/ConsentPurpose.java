package com.aifashionvault.privacy;

/**
 * The consent purposes this product actually asks for.
 *
 * <p>A closed vocabulary, not free text: consent is enforced by matching a
 * named purpose at the point of processing, and a typo in a string literal
 * would silently create a grant that never matches. The wire values are the
 * stored {@code purpose} strings.</p>
 */
public enum ConsentPurpose {

	/** Processing the user's body photo to build their model. */
	BODY_IMAGE_PROCESSING("body_image_processing"),

	/** Sending the user's body photo to a third-party AI image provider. */
	THIRD_PARTY_AI_TRANSFER("third_party_ai_transfer"),

	/** Keeping the body photo and generated results for the stated retention window. */
	RETENTION("retention");

	private final String wireName;

	ConsentPurpose(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return this.wireName;
	}

	/**
	 * Parses a stored/requested purpose string back into the closed vocabulary.
	 * An unrecognised value is empty rather than a silent new purpose: a purpose
	 * the product cannot enforce must never be recorded.
	 */
	public static java.util.Optional<ConsentPurpose> fromWire(String value) {
		if (value == null) {
			return java.util.Optional.empty();
		}
		String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
		for (ConsentPurpose purpose : values()) {
			if (purpose.wireName.equals(normalized)) {
				return java.util.Optional.of(purpose);
			}
		}
		return java.util.Optional.empty();
	}
}
