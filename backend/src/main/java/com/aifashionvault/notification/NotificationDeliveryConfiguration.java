package com.aifashionvault.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties(prefix = "vault.notification")
public class NotificationDeliveryConfiguration {

	private String fcmBaseUrl = "https://fcm.googleapis.com";
	private String projectId = "";
	private String accessToken = "";
	private String serviceAccountJson = "";
	private boolean allowStaticAccessToken = false;
	private int maxAttempts = 10;
	private int minRetrySeconds = 10;
	private int ttlHours = 24;

	public String getFcmBaseUrl() {
		return this.fcmBaseUrl;
	}

	public void setFcmBaseUrl(String fcmBaseUrl) {
		this.fcmBaseUrl = fcmBaseUrl;
	}

	public String getProjectId() {
		return this.projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}

	public String getAccessToken() {
		return this.accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	/**
	 * Google service-account JSON used to mint short-lived access tokens (C20). When
	 * present it is the production credential source and {@link #getAccessToken()} is
	 * ignored.
	 */
	public String getServiceAccountJson() {
		return this.serviceAccountJson;
	}

	public void setServiceAccountJson(String serviceAccountJson) {
		this.serviceAccountJson = serviceAccountJson;
	}

	/**
	 * Explicit opt-in for the pasted static token. Defaults to {@code false} so a
	 * short-lived pasted value is never the production credential; local runs and tests
	 * must ask for it deliberately.
	 */
	public boolean isAllowStaticAccessToken() {
		return this.allowStaticAccessToken;
	}

	public void setAllowStaticAccessToken(boolean allowStaticAccessToken) {
		this.allowStaticAccessToken = allowStaticAccessToken;
	}

	public int getMaxAttempts() {
		return this.maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be >= 1");
		}
		this.maxAttempts = maxAttempts;
	}

	public int getMinRetrySeconds() {
		return this.minRetrySeconds;
	}

	public void setMinRetrySeconds(int minRetrySeconds) {
		if (minRetrySeconds < 1) {
			throw new IllegalArgumentException("minRetrySeconds must be >= 1");
		}
		this.minRetrySeconds = minRetrySeconds;
	}

	public int getTtlHours() {
		return this.ttlHours;
	}

	public void setTtlHours(int ttlHours) {
		if (ttlHours < 1) {
			throw new IllegalArgumentException("ttlHours must be >= 1");
		}
		this.ttlHours = ttlHours;
	}

	public Duration ttl() {
		return Duration.ofHours(this.ttlHours);
	}

	public Duration minRetry() {
		return Duration.ofSeconds(this.minRetrySeconds);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(NotificationDeliveryConfiguration.class)
	static class Registrar {
	}
}
