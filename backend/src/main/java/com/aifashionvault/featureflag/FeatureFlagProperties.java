package com.aifashionvault.featureflag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "vault.feature-flags")
public class FeatureFlagProperties {

	private boolean multiItem = true;
	private boolean naturalLanguageStudio = true;
	private boolean tryonMax = true;
	private boolean hdGeneration = true;
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

	public boolean isEnabled(FeatureFlagName name) {
		if (name == null) {
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

	public void setEnabled(FeatureFlagName name, boolean enabled) {
		if (name == null) {
			return;
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
