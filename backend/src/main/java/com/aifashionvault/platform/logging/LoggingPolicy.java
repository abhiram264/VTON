package com.aifashionvault.platform.logging;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public constants and policy definitions for structured JSON logging.
 * Enforces the frozen slice-66 allowlist and NeverLog exclusion patterns.
 */
public final class LoggingPolicy {

	public static final String MASK = "***";

	public static final String KEY_REQUEST_ID = "request_id";
	public static final String KEY_USER_ID = "user_id";
	public static final String KEY_GENERATION_ID = "generation_id";
	public static final String KEY_PROVIDER = "provider";
	public static final String KEY_STATUS = "status";
	public static final String KEY_LATENCY_MS = "latency_ms";
	public static final String KEY_ERROR_CODE = "error_code";

	public static final List<String> LOG_FIELDS = List.of(
		"ts",
		"level",
		"logger",
		"msg",
		KEY_REQUEST_ID,
		KEY_USER_ID,
		KEY_GENERATION_ID,
		KEY_PROVIDER,
		KEY_STATUS,
		KEY_LATENCY_MS,
		KEY_ERROR_CODE
	);

	public static final Set<String> MDC_KEYS = Set.of(
		KEY_REQUEST_ID,
		KEY_USER_ID,
		KEY_GENERATION_ID,
		KEY_PROVIDER,
		KEY_STATUS,
		KEY_LATENCY_MS,
		KEY_ERROR_CODE
	);

	public static final List<String> NEVER_LOG_NAMES = List.of(
		"image",
		"image_bytes",
		"image_base64",
		"payload",
		"content",
		"data",
		"signed_url",
		"authorization",
		"token",
		"id_token",
		"refresh_token",
		"api_key",
		"secret",
		"provider_secret",
		"password"
	);

	public static final List<Pattern> NEVER_LOG_VALUE_PATTERNS = List.of(
		// URLs carrying signature or token query parameters
		Pattern.compile("(?:https?:)?//[^\\s\"'>\\\\]*?(?:X-Amz-Signature|Signature=|X-Goog-Signature|token=)[^\\s\"'>\\\\]*", Pattern.CASE_INSENSITIVE),
		// Data URIs with base64
		Pattern.compile("data:[^;,\\s\"'>\\\\]*(?:;[^;,\\s\"'>\\\\]*)*;base64,[A-Za-z0-9+/=_-]+", Pattern.CASE_INSENSITIVE),
		// JPEG and PNG base64 headers
		Pattern.compile("(?:/9j/|iVBORw0KGgo)[A-Za-z0-9+/=_-]+"),
		// Base64 blobs: standard (+/) and URL-safe (-_) base64 characters with padding '=' or 40+ chars without padding (excluding UUIDs)
		Pattern.compile("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{12,}={1,2}(?![A-Za-z0-9+/=_-])"),
		Pattern.compile("(?<![A-Za-z0-9+/_-])(?!(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}))[A-Za-z0-9+/_-]{40,}(?![A-Za-z0-9+/=_-])"),
		// Raw byte array string representations
		Pattern.compile("\\[B@[0-9a-fA-F]+"),
		Pattern.compile("byte\\[\\d+\\]"),
		// Bearer tokens
		Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/-]+", Pattern.CASE_INSENSITIVE),
		// Assignment-like key-value structures for all 15 NeverLog names (name=value, name: value, quoted or unquoted)
		Pattern.compile("(?:^|(?<=[^A-Za-z0-9_]))([\"']?(?:image_bytes|image_base64|image|provider_secret|refresh_token|id_token|auth_token|api_key|signed_url|authorization|password|payload|content|secret|token|data)[\"']?\\s*[:=]\\s*)(?:([\"'])(?:(?!\\2).)*\\2|(\\{[^}\\r\\n]*\\}|\\[[^\\]\\r\\n]*\\])|((?:Basic|Bearer)\\s+[^\\s,\"`}\\]]+)|(?:(?!(?:\\s+[\"']?(?:image_bytes|image_base64|image|provider_secret|refresh_token|id_token|auth_token|api_key|signed_url|authorization|password|payload|content|secret|token|data)[\"']?\\s*[:=]))[^,;\"`}\\]\\)\\r\\n])+)", Pattern.CASE_INSENSITIVE)
	);

	private LoggingPolicy() {
	}

	/**
	 * Masks sensitive substrings in the given input string using the NeverLog value patterns.
	 *
	 * @param input raw input string
	 * @return masked string, or null if input was null
	 */
	public static String mask(String input) {
		if (input == null) {
			return null;
		}
		if (input.isEmpty()) {
			return "";
		}
		String masked = input;
		for (Pattern pattern : NEVER_LOG_VALUE_PATTERNS) {
			if (pattern.pattern().contains("image_bytes|image_base64")) {
				masked = pattern.matcher(masked).replaceAll("$1" + MASK);
			}
			else {
				masked = pattern.matcher(masked).replaceAll(MASK);
			}
		}
		return masked;
	}
}
