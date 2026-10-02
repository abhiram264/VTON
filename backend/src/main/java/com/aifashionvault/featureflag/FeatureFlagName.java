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
 * <p>Unsupported today: natural-language Studio (C21) is incomplete, HD generation
 * is advertised without a consumer, and the new quality gate (C18) is a future,
 * externally blocked capability.
 *
 * <p>{@code MULTI_ITEM} IS supported: the C12 create path is implemented
 * ({@code POST /generations/multi}, admission-gated, frozen-intent tested) and its
 * real switch is the server-side {@code vault.generation.multi-item-enabled}
 * property, which ships OFF. The advertised flag therefore defaults OFF while
 * remaining controllable by the backend — conservative posture, backend
 * authoritative.
 */
public enum FeatureFlagName {
	MULTI_ITEM(true),
	NATURAL_LANGUAGE_STUDIO(false),
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
