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

	public int getMaxAttempts() {
		return this.maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public int getMinRetrySeconds() {
		return this.minRetrySeconds;
	}

	public void setMinRetrySeconds(int minRetrySeconds) {
		this.minRetrySeconds = minRetrySeconds;
	}

	public int getTtlHours() {
		return this.ttlHours;
	}

	public void setTtlHours(int ttlHours) {
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
