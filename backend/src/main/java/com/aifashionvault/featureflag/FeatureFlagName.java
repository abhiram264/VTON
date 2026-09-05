package com.aifashionvault.featureflag;

public enum FeatureFlagName {
	MULTI_ITEM(true),
	NATURAL_LANGUAGE_STUDIO(true),
	TRYON_MAX(true),
	HD_GENERATION(true),
	SHARE_IMPORT_ANDROID(true),
	SHARE_IMPORT_IOS(true),
	NEW_QUALITY_GATE(false);

	private final boolean defaultEnabled;

	FeatureFlagName(boolean defaultEnabled) {
		this.defaultEnabled = defaultEnabled;
	}

	public boolean defaultEnabled() {
		return this.defaultEnabled;
	}

	public boolean isDefaultEnabled() {
		return this.defaultEnabled;
	}
}
