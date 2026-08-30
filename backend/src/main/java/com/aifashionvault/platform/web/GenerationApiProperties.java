package com.aifashionvault.platform.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "vault.generation")
public class GenerationApiProperties {

	private boolean multiItemEnabled = false;
	private boolean admissionPaused = false;
	private boolean attestationRequired = false;

	public boolean isMultiItemEnabled() {
		return this.multiItemEnabled;
	}

	public void setMultiItemEnabled(boolean multiItemEnabled) {
		this.multiItemEnabled = multiItemEnabled;
	}

	public boolean isAdmissionPaused() {
		return this.admissionPaused;
	}

	public void setAdmissionPaused(boolean admissionPaused) {
		this.admissionPaused = admissionPaused;
	}

	public boolean isAttestationRequired() {
		return this.attestationRequired;
	}

	public void setAttestationRequired(boolean attestationRequired) {
		this.attestationRequired = attestationRequired;
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GenerationApiProperties.class)
	static class Registrar {
	}
}
