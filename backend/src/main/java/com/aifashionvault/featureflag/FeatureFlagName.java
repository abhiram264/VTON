package com.aifashionvault.featureflag;

/**
 * Capability flags advertised by {@code GET /feature-flags}.
 *
 * <p>{@code supported} records whether this release has a working backing path for
 * the capability. C22 requires unsupported capabilities to default OFF in release
 * builds and to stay OFF no matter what a configuration value, an offline flag
 * fetch or a remote override says. The flag authority therefore fails closed: an
 * unsupported capability reports disabled and cannot be enabled through
 * {@link FeatureFlags#override} or {@link FeatureFlagProperties#setEnabled}.
 *
 * <p>Unsupported today: HD generation is advertised without a consumer, and the
 * new quality gate (C18) is a future, externally blocked capability. Multi-item
 * creation (C12) and natural-language Studio (C21) now HAVE a mounted backing
 * path, so they are supported-but-default-OFF: the property decides, and the
 * default keeps them out of a release until they are approved.
 */
public enum FeatureFlagName {
	MULTI_ITEM(false),
	NATURAL_LANGUAGE_STUDIO(true),
	TRYON_MAX(true),
	HD_GENERATION(false),
	SHARE_IMPORT_ANDROID(true),
	SHARE_IMPORT_IOS(true),
	NEW_QUALITY_GATE(false);

	private final boolean supported;

	FeatureFlagName(boolean supported) {
		this.supported = supported;
	}

	public boolean isSupported() {
		return this.supported;
	}
}
