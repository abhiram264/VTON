package com.aifashionvault.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class FcmClient {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Set<String> FORBIDDEN_KEYS = Set.of(
		"avatar_url",
		"signed_s3_url",
		"image_url",
		"base64_image",
		"providerJobId",
		"provider_job_id",
		"storageKey",
		"storage_key",
		"objectKey",
		"object_key",
		"signedUrl",
		"signed_url",
		"imageUrl",
		"image",
		"mediaUrl",
		"media_url");

	private final String baseUrl;
	private final String projectId;
	private final Supplier<String> accessToken;
	private final Duration ttl;
	private final HttpClient httpClient;

	public FcmClient(
		String baseUrl,
		String projectId,
		Supplier<String> accessToken,
		Duration ttl,
		HttpClient httpClient
	) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalArgumentException("baseUrl is required");
		}
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalArgumentException("projectId is required");
		}
		if (accessToken == null) {
			throw new IllegalArgumentException("accessToken is required");
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("ttl is required");
		}
		if (httpClient == null) {
			throw new IllegalArgumentException("httpClient is required");
		}
		this.baseUrl = stripTrailingSlash(baseUrl);
		this.projectId = projectId;
		this.accessToken = accessToken;
		this.ttl = ttl;
		this.httpClient = httpClient;
	}

	public FcmSendResult send(String deviceToken, Map<String, String> data, Instant now) {
		if (deviceToken == null || deviceToken.isBlank()) {
			throw new IllegalArgumentException("deviceToken is required");
		}
		if (data == null) {
			throw new IllegalArgumentException("data is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		rejectUnsafePayload(data);
		String token = accessToken.get();
		if (token == null || token.isBlank()) {
			throw new IllegalStateException("access token is required");
		}
		byte[] body = encode(messageBody(deviceToken, data, now));
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/v1/projects/" + projectId + "/messages:send"))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			Duration retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), now);
			if (status >= 200 && status < 300) {
				return FcmSendResult.accepted(status, messageId(response.body()));
			}
			return FcmSendResult.rejected(status, isRetryable(status), retryAfter);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return FcmSendResult.rejected(0, true, null);
		}
		catch (IOException ex) {
			return FcmSendResult.rejected(0, true, null);
		}
	}

	@Override
	public String toString() {
		return "FcmClient[]";
	}

	private Map<String, Object> messageBody(String deviceToken, Map<String, String> data, Instant now) {
		Map<String, Object> android = new LinkedHashMap<>();
		android.put("ttl", ttl.toSeconds() + "s");
		Map<String, String> apnsHeaders = new LinkedHashMap<>();
		apnsHeaders.put("apns-expiration", Long.toString(now.plus(ttl).getEpochSecond()));
		Map<String, Object> apns = new LinkedHashMap<>();
		apns.put("headers", apnsHeaders);
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("token", deviceToken);
		message.put("data", data);
		message.put("android", android);
		message.put("apns", apns);
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("message", message);
		return root;
	}

	private static void rejectUnsafePayload(Map<String, String> data) {
		for (Map.Entry<String, String> entry : data.entrySet()) {
			if (FORBIDDEN_KEYS.contains(entry.getKey())) {
				throw new IllegalArgumentException("payload must not contain " + entry.getKey());
			}
			if (isHttpUrl(entry.getValue())) {
				throw new IllegalArgumentException("payload must not contain an http(s) URL");
			}
		}
	}

	private static boolean isRetryable(int status) {
		return status == 429 || status >= 500;
	}

	static Duration parseRetryAfter(String header, Instant now) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.strip();
		try {
			return Duration.ofSeconds(Long.parseLong(value));
		}
		catch (NumberFormatException ignored) {
			try {
				Instant until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				Duration delay = Duration.between(now, until);
				return delay.isNegative() ? Duration.ZERO : delay;
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
	}

	private static String messageId(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonNode node = MAPPER.readTree(body);
			if (node == null || !node.has("name")) {
				return null;
			}
			String name = node.get("name").stringValue();
			return name == null || name.isBlank() ? null : name;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private static byte[] encode(Map<String, Object> payload) {
		try {
			return MAPPER.writeValueAsBytes(payload);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("FCM payload could not be serialized", ex);
		}
	}

	private static boolean isHttpUrl(String value) {
		if (value == null) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	public record FcmSendResult(
		int statusCode,
		boolean accepted,
		boolean retryable,
		Duration retryAfter,
		String providerMessageId
	) {

		static FcmSendResult accepted(int statusCode, String providerMessageId) {
			return new FcmSendResult(statusCode, true, false, null, providerMessageId);
		}

		static FcmSendResult rejected(int statusCode, boolean retryable, Duration retryAfter) {
			return new FcmSendResult(statusCode, false, retryable, retryAfter, null);
		}
	}
}
