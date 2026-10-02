package com.aifashionvault.featureflag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Bound configuration for the capability flags ({@code vault.feature-flags.*}).
 *
 * <p>Defaults mirror the release posture required by C22: unsupported capabilities
 * default OFF. The binding surface stays fail-closed — {@link #isEnabled} returns
 * false for an unsupported capability even if a configuration value (environment
 * variable, properties file or remote override) sets it to true, and
 * {@link #setEnabled} refuses to turn one on.
 */
@ConfigurationProperties(prefix = "vault.feature-flags")
public class FeatureFlagProperties {

	private boolean multiItem = false;
	private boolean naturalLanguageStudio = false;
	private boolean tryonMax = true;
	private boolean hdGeneration = false;
	private boolean shareImportAndroid = true;
	private boolean shareImportIos = true;
	private boolean newQualityGate = false;

	public boolean isMultiItem() {
		return this.multiItem;
	}

	public void setMultiItem(boolean multiItem) {
		this.multiItem = multiItem;
	}

	public boolean isNaturalLanguageStudio() {
		return this.naturalLanguageStudio;
	}

	public void setNaturalLanguageStudio(boolean naturalLanguageStudio) {
		this.naturalLanguageStudio = naturalLanguageStudio;
	}

	public boolean isTryonMax() {
		return this.tryonMax;
	}

	public void setTryonMax(boolean tryonMax) {
		this.tryonMax = tryonMax;
	}

	public boolean isHdGeneration() {
		return this.hdGeneration;
	}

	public void setHdGeneration(boolean hdGeneration) {
		this.hdGeneration = hdGeneration;
	}

	public boolean isShareImportAndroid() {
		return this.shareImportAndroid;
	}

	public void setShareImportAndroid(boolean shareImportAndroid) {
		this.shareImportAndroid = shareImportAndroid;
	}

	public boolean isShareImportIos() {
		return this.shareImportIos;
	}

	public void setShareImportIos(boolean shareImportIos) {
		this.shareImportIos = shareImportIos;
	}

	public boolean isNewQualityGate() {
		return this.newQualityGate;
	}

	public void setNewQualityGate(boolean newQualityGate) {
		this.newQualityGate = newQualityGate;
	}

	/**
	 * Effective value from bound configuration. Unsupported capabilities fail closed
	 * and are never reported enabled, whatever the bound value says.
	 */
	public boolean isEnabled(FeatureFlagName name) {
		if (name == null || !name.isSupported()) {
			return false;
		}
		return switch (name) {
			case MULTI_ITEM -> this.multiItem;
			case NATURAL_LANGUAGE_STUDIO -> this.naturalLanguageStudio;
			case TRYON_MAX -> this.tryonMax;
			case HD_GENERATION -> this.hdGeneration;
			case SHARE_IMPORT_ANDROID -> this.shareImportAndroid;
			case SHARE_IMPORT_IOS -> this.shareImportIos;
			case NEW_QUALITY_GATE -> this.newQualityGate;
		};
	}

	/**
	 * Programmatic binding surface. Disabling any capability is allowed; enabling an
	 * unsupported one is refused so a flag source cannot turn on a capability the
	 * release cannot serve.
	 */
	public void setEnabled(FeatureFlagName name, boolean enabled) {
		if (name == null) {
			return;
		}
		if (enabled && !name.isSupported()) {
			throw new UnsupportedOperationException(
				"feature flag " + name + " is not supported by this release and cannot be enabled");
		}
		switch (name) {
			case MULTI_ITEM -> this.multiItem = enabled;
			case NATURAL_LANGUAGE_STUDIO -> this.naturalLanguageStudio = enabled;
			case TRYON_MAX -> this.tryonMax = enabled;
			case HD_GENERATION -> this.hdGeneration = enabled;
			case SHARE_IMPORT_ANDROID -> this.shareImportAndroid = enabled;
			case SHARE_IMPORT_IOS -> this.shareImportIos = enabled;
			case NEW_QUALITY_GATE -> this.newQualityGate = enabled;
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(FeatureFlagProperties.class)
	static class Registrar {
	}
}
