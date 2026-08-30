package com.aifashionvault.platform.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.aifashionvault.generation.CallbackInboxStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@ConditionalOnBean(CallbackInboxStore.class)
public class GenerationCallbackController {

	static final int MIN_TOKEN_HEX_CHARS = 64;
	static final int MAX_BODY_BYTES = 8192;
	private static final Set<String> SAFE_HINTS = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CallbackInboxStore inboxStore;
	private final Clock clock;

	public GenerationCallbackController(CallbackInboxStore inboxStore, Clock clock) {
		if (inboxStore == null) {
			throw new IllegalArgumentException("inboxStore is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.inboxStore = inboxStore;
		this.clock = clock;
	}

	@PostMapping("/generation-callbacks/{token}")
	public ResponseEntity<Void> receive(
		@PathVariable String token,
		@RequestBody(required = false) byte[] body
	) {
		if (!validToken(token)) {
			return ResponseEntity.notFound().build();
		}
		Parsed parsed = parse(body);
		if (!inboxStore.accept(sha256Hex(token), parsed.candidate(), parsed.hint(), clock.instant())) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.noContent().build();
	}

	static boolean validToken(String token) {
		if (token == null || token.length() < MIN_TOKEN_HEX_CHARS) {
			return false;
		}
		for (int i = 0; i < token.length(); i++) {
			char ch = token.charAt(i);
			boolean hex = (ch >= '0' && ch <= '9')
				|| (ch >= 'a' && ch <= 'f')
				|| (ch >= 'A' && ch <= 'F');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	public static String sha256Hex(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte value : digest) {
				hex.append(Character.forDigit((value >> 4) & 0xF, 16));
				hex.append(Character.forDigit(value & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}

	private static Parsed parse(byte[] body) {
		if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
			return Parsed.EMPTY;
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		}
		catch (RuntimeException ex) {
			return Parsed.EMPTY;
		}
		if (root == null || !root.isObject()) {
			return Parsed.EMPTY;
		}
		return new Parsed(readCandidate(root), readHint(root));
	}

	private static String readCandidate(JsonNode root) {
		String value = firstText(root, "providerJobId", "provider_job_id");
		if (value == null || value.isBlank()) {
			return null;
		}
		String stripped = value.strip();
		String lower = stripped.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return null;
		}
		if (stripped.length() > 128) {
			return null;
		}
		for (int i = 0; i < stripped.length(); i++) {
			char ch = stripped.charAt(i);
			boolean allowed = (ch >= '0' && ch <= '9')
				|| (ch >= 'A' && ch <= 'Z')
				|| (ch >= 'a' && ch <= 'z')
				|| ch == '_'
				|| ch == '-'
				|| ch == '.'
				|| ch == ':';
			if (!allowed) {
				return null;
			}
		}
		return stripped;
	}

	private static String readHint(JsonNode root) {
		String value = firstText(root, "terminalHint", "status");
		if (value == null) {
			return null;
		}
		String normalized = value.strip().toUpperCase(Locale.ROOT);
		return SAFE_HINTS.contains(normalized) ? normalized : null;
	}

	private static String firstText(JsonNode root, String... names) {
		for (String name : names) {
			JsonNode node = root.get(name);
			if (node != null && node.isString()) {
				String value = node.stringValue();
				if (value != null && !value.isBlank()) {
					return value;
				}
			}
		}
		return null;
	}

	private record Parsed(String candidate, String hint) {
		private static final Parsed EMPTY = new Parsed(null, null);
	}
}
