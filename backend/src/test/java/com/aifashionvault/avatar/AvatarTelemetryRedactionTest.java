package com.aifashionvault.avatar;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;


import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.web.AvatarProblemResponse;
import com.aifashionvault.platform.web.AvatarResourceResponse;
import com.aifashionvault.platform.web.CreateUploadResponse;
import com.aifashionvault.shared.UploadId;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarTelemetryRedactionTest {

	private static final String PROFILE_ID = "11111111-1111-1111-1111-111111111111";

	private static final String IMAGE_ID = "33333333-3333-3333-3333-333333333333";

	private static final String UPLOAD_ID = "22222222-2222-2222-2222-222222222222";

	private static final String SIGNED_URL = "https://example.invalid/avatars/get?X-Amz-Signature=redact-me-token";

	private static final URI SIGNED_URI = URI.create(
		"https://example.invalid/staging/put?X-Amz-Signature=redact-me-token");

	private static final Instant EXPIRES_AT = Instant.parse("2026-08-23T12:00:00Z");

	private static final Pattern LOG_CALL = Pattern.compile(
		"\\b(?:LOG|log|logger|LOGGER)\\s*\\.\\s*(?:log|info|warn|warning|error|debug|trace|severe)\\s*\\(");

	private static final Pattern STORAGE_KEY_IN_LOG = Pattern.compile("storageKey(?:\\(\\))?\\.value\\(\\)");

	private static final Pattern FORBIDDEN_SOURCE_LITERAL = Pattern.compile(
		"base64|signedUrl|content://|ph://|photos://|assets-library://|file://|data:image",
		Pattern.CASE_INSENSITIVE);

	private static final Pattern QUEUE_LITERAL = Pattern.compile(
		"GenerationWorkQueue|\\bSqs\\b|\\bSQS\\b|software\\.amazon\\.awssdk\\.services\\.sqs");

	@Test
	void avatarResourceResponseToStringOmitsSignedDisplayUrl() {
		AvatarResourceResponse response = new AvatarResourceResponse(
			PROFILE_ID,
			IMAGE_ID,
			1,
			8,
			6,
			"image/jpeg",
			SIGNED_URL,
			EXPIRES_AT,
			List.of());

		assertThat(response.toString()).isEqualTo(
			"AvatarResourceResponse[id=" + PROFILE_ID
				+ ", imageId=" + IMAGE_ID
				+ ", revision=1, width=8, height=6, mediaType=image/jpeg"
				+ ", displayExpiresAt=" + EXPIRES_AT
				+ ", warnings=[]]");
		assertThat(response.toString()).doesNotContain(SIGNED_URL);
		assertThat(response.toString()).doesNotContain("displayUrl");
		assertNoSensitiveTelemetry(response.toString());
	}

	@Test
	void avatarExceptionMessageIsOnlyTheCodeName() {
		AvatarException notFound = new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);

		assertThat(notFound.getMessage()).isEqualTo("AVATAR_NOT_FOUND");
		assertThat(notFound.getMessage()).isEqualTo(AvatarProblemCode.AVATAR_NOT_FOUND.name());
		assertNoSensitiveTelemetry(notFound.getMessage());
		assertNoSensitiveTelemetry(notFound.toString());

		for (AvatarProblemCode code : AvatarProblemCode.values()) {
			AvatarException exception = new AvatarException(code);
			assertThat(exception.getMessage()).isEqualTo(code.name());
			assertThat(exception.getMessage())
				.doesNotContain("http")
				.doesNotContain("signed")
				.doesNotContain("storage key")
				.doesNotContain("base64");
			assertNoSensitiveTelemetry(exception.getMessage());
			assertNoSensitiveTelemetry(exception.toString());
		}
	}

	@Test
	void avatarProblemResponseToStringContainsOnlyTheCode() {
		AvatarProblemResponse response = new AvatarProblemResponse("AVATAR_NOT_FOUND");

		assertThat(response.toString()).isEqualTo("AvatarProblemResponse[code=AVATAR_NOT_FOUND]");
		assertNoSensitiveTelemetry(response.toString());
	}

	@Test
	void avatarPreflightResultToStringOmitsHexDumpsAndRawBytes() {
		AvatarPreflightResult result = new AvatarPreflightResult(
			AvatarMediaType.JPEG,
			8,
			6,
			List.of(AvatarProblemCode.AVATAR_TOO_DARK.name()),
			AvatarPreflightPolicy.VERSION);

		assertThat(result.toString()).isEqualTo(
			"AvatarPreflightResult[mediaType=JPEG, width=8, height=6, warnings=[AVATAR_TOO_DARK], "
				+ "policyVersion=avatar-preflight-v1]");
		assertThat(result.toString())
			.doesNotContain("0x")
			.doesNotContain("byte[")
			.doesNotContain("[B@")
			.doesNotContain("FF D8")
			.doesNotContain("/9j/")
			.doesNotContain("iVBORw0KGgo");
		assertThat(result.toString().toLowerCase(Locale.ROOT))
			.doesNotContain("landmark")
			.doesNotContain("tensor")
			.doesNotContain("bbox")
			.doesNotContain("box")
			.doesNotContain("confidence")
			.doesNotContain("score")
			.doesNotContain("detector")
			.doesNotContain("evaluator")
			.doesNotContain("detail");
		assertNoSensitiveTelemetry(result.toString());
	}

	@Test
	void createUploadResponseToStringOmitsSignedUrl() {
		CreateUploadResponse response = new CreateUploadResponse(
			UPLOAD_ID,
			"PUT",
			SIGNED_URL,
			Map.of("content-type", "image/jpeg"),
			EXPIRES_AT);

		assertThat(response.toString()).doesNotContain(SIGNED_URL);
		assertThat(response.toString()).doesNotContain("url=");
		assertNoSensitiveTelemetry(response.toString());
	}

	@Test
	void presignedAccessToStringOmitsSignedUri() {
		PresignedAccess access = new PresignedAccess(
			new ObjectRef(StorageBoundary.TEMP, "temp/u/uploads/id"),
			"PUT",
			SIGNED_URI,
			Map.of("content-type", "image/jpeg"),
			EXPIRES_AT);

		assertThat(access.toString()).doesNotContain(SIGNED_URI.toString());
		assertThat(access.toString()).doesNotContain("uri=");
		assertNoSensitiveTelemetry(access.toString());
	}

	@Test
	void uploadCapabilityToStringOmitsSignedUri() {
		UploadCapability capability = new UploadCapability(
			new UploadId(UUID.fromString(UPLOAD_ID)),
			"PUT",
			SIGNED_URI,
			Map.of("content-type", "image/jpeg"),
			EXPIRES_AT);

		assertThat(capability.toString()).doesNotContain(SIGNED_URI.toString());
		assertThat(capability.toString()).doesNotContain("uri=");
		assertThat(capability.toString()).contains(UPLOAD_ID);
		assertNoSensitiveTelemetry(capability.toString());
	}

	@Test
	void avatarSourcesDoNotLogSensitiveMediaOrPublishQueueMessages() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path file : avatarProductionSources()) {
			scanSource(file, violations);
		}
		assertThat(violations).isEmpty();
	}

	private static void scanSource(Path file, List<String> violations) throws IOException {
		String source = Files.readString(file, StandardCharsets.UTF_8);
		String display = file.getFileName().toString();
		List<String> lines = source.lines().toList();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (isCommentLine(line)) {
				continue;
			}
			Matcher forbidden = FORBIDDEN_SOURCE_LITERAL.matcher(line);
			if (forbidden.find()) {
				violations.add(display + ":" + (i + 1) + " forbidden literal " + forbidden.group());
			}
			Matcher queue = QUEUE_LITERAL.matcher(line);
			if (queue.find()) {
				violations.add(display + ":" + (i + 1) + " queue publish " + queue.group());
			}
		}
		Matcher logCall = LOG_CALL.matcher(source);
		while (logCall.find()) {
			int open = source.indexOf('(', logCall.start());
			String call = extractBalanced(source, open);
			if (STORAGE_KEY_IN_LOG.matcher(call).find()) {
				violations.add(display + " log concatenates storageKey.value()");
			}
			if (containsUriMaterial(call)) {
				violations.add(display + " log concatenates URI or capability material");
			}
			if (containsAuthMaterial(call)) {
				violations.add(display + " log includes Authorization or secret material");
			}
		}
	}

	private static boolean containsUriMaterial(String call) {
		String lower = call.toLowerCase(Locale.ROOT);
		return lower.contains("http://")
			|| lower.contains("https://")
			|| lower.contains("uri.tostring()")
			|| call.contains(".uri()")
			|| call.contains("displayUrl")
			|| call.contains("signedUrl");
	}

	private static boolean containsAuthMaterial(String call) {
		String lower = call.toLowerCase(Locale.ROOT);
		return lower.contains("authorization")
			|| lower.contains("bearer ")
			|| lower.contains("x-amz-")
			|| lower.contains("x-api-key");
	}

	private static String extractBalanced(String source, int openParen) {
		if (openParen < 0 || openParen >= source.length()) {
			return "";
		}
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		for (int i = openParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inString) {
				if (escape) {
					escape = false;
				}
				else if (c == '\\') {
					escape = true;
				}
				else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
				continue;
			}
			if (c == '(') {
				depth++;
			}
			else if (c == ')') {
				depth--;
				if (depth == 0) {
					return source.substring(openParen, i + 1);
				}
			}
		}
		return source.substring(openParen);
	}

	private static void assertNoSensitiveTelemetry(String text) {
		assertThat(text)
			.doesNotContain(SIGNED_URL)
			.doesNotContain(SIGNED_URI.toString())
			.doesNotContain("redact-me-token")
			.doesNotContain("http://")
			.doesNotContain("https://")
			.doesNotContain("X-Amz-")
			.doesNotContain("Signature=")
			.doesNotContain("data:image")
			.doesNotContain("base64")
			.doesNotContain("/9j/")
			.doesNotContain("iVBORw0KGgo")
			.doesNotContain("content://")
			.doesNotContain("ph://")
			.doesNotContain("photos://")
			.doesNotContain("assets-library://")
			.doesNotContain("file://")
			.doesNotContain("Authorization")
			.doesNotContain("Bearer ")
			.doesNotContain("0x")
			.doesNotContain("byte[")
			.doesNotContain("[B@")
			.doesNotContain("FF D8");
	}

	private static boolean isCommentLine(String line) {
		String trimmed = line.trim();
		return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*");
	}

	private static List<Path> avatarProductionSources() throws IOException {
		List<Path> files = new ArrayList<>();
		Path avatarDir = locate("src", "main", "java", "com", "aifashionvault", "avatar");
		try (Stream<Path> walk = Files.walk(avatarDir)) {
			walk.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".java"))
				.forEach(files::add);
		}
		Path controller = locate("src", "main", "java", "com", "aifashionvault", "platform", "web")
			.resolve("AvatarsController.java");
		if (Files.isRegularFile(controller)) {
			files.add(controller);
		}
		return List.copyOf(files);
	}

	private static Path locate(String first, String... more) {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(first, more));
			if (Files.isDirectory(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(first, more));
			if (Files.isDirectory(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + Path.of(first, more) + " from " + cwd);
	}
}
