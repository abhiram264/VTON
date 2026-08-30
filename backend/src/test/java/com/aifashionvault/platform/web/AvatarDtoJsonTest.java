package com.aifashionvault.platform.web;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.aifashionvault.avatar.AvatarException;
import com.aifashionvault.avatar.AvatarProblemCode;
import com.aifashionvault.avatar.AvatarWorkflow;
import com.aifashionvault.platform.objectstore.ObjectStore;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AvatarDtoJsonTest {

	private static final String PROFILE_ID = "11111111-1111-1111-1111-111111111111";

	private static final String IMAGE_ID = "33333333-3333-3333-3333-333333333333";

	private static final String UPLOAD_ID = "22222222-2222-2222-2222-222222222222";

	private static final String SIGNED_URL = "https://example.invalid/avatars/get?X-Amz-Signature=redact-me-token";

	private static final Instant EXPIRES_AT = Instant.parse("2026-08-23T12:00:00Z");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final List<String> ALLOWED_CODES = List.of(
		"AVATAR_UNREADABLE",
		"AVATAR_UNSUPPORTED_FORMAT",
		"AVATAR_TYPE_MISMATCH",
		"AVATAR_RESOLUTION_INSUFFICIENT",
		"AVATAR_BYTES_EXCEEDED",
		"AVATAR_DIMENSIONS_EXCEEDED",
		"AVATAR_PIXELS_EXCEEDED",
		"AVATAR_ASPECT_RATIO",
		"AVATAR_TOO_BLURRED",
		"AVATAR_TOO_DARK",
		"AVATAR_CONFLICT",
		"AVATAR_STALE_REVISION",
		"AVATAR_NOT_FOUND",
		"AVATAR_UPLOAD_NOT_CLAIMABLE",
		"AVATAR_MEDIA_MISSING",
		"AVATAR_PERSON_NOT_SINGLE",
		"AVATAR_FACE_NOT_VISIBLE",
		"AVATAR_BODY_NOT_VISIBLE",
		"AVATAR_PREFLIGHT_UNAVAILABLE");

	@Test
	void avatarUploadRequestHasUploadIdOnly() {
		assertThat(componentNames(AvatarUploadRequest.class)).containsExactly("uploadId");
		assertThat(componentTypes(AvatarUploadRequest.class)).containsExactly(String.class);
		assertThat(componentNames(AvatarUploadRequest.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl", "contentType", "contentLength");
	}

	@Test
	void avatarUploadRequestSerializesCanonicalFieldNames() {
		String json = MAPPER.writeValueAsString(new AvatarUploadRequest(UPLOAD_ID));

		assertThat(json).isEqualTo("{\"uploadId\":\"" + UPLOAD_ID + "\"}");
	}

	@Test
	void avatarUploadRequestRejectsNullOrBlank() {
		assertThatThrownBy(() -> new AvatarUploadRequest(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarUploadRequest(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarUploadRequest("   "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void avatarResourceResponseHasExactComponentOrder() {
		assertThat(componentNames(AvatarResourceResponse.class)).containsExactly(
			"id",
			"imageId",
			"revision",
			"width",
			"height",
			"mediaType",
			"displayUrl",
			"displayExpiresAt",
			"warnings");
		assertThat(componentTypes(AvatarResourceResponse.class)).containsExactly(
			String.class,
			String.class,
			int.class,
			int.class,
			int.class,
			String.class,
			String.class,
			Instant.class,
			List.class);
		assertThat(componentNames(AvatarResourceResponse.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl");
	}

	@Test
	void avatarResourceResponseSerializesExactJson() {
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

		String json = MAPPER.writeValueAsString(response);
		JsonNode node = MAPPER.readTree(json);

		assertThat(node.propertyNames()).containsExactly(
			"id",
			"imageId",
			"revision",
			"width",
			"height",
			"mediaType",
			"displayUrl",
			"displayExpiresAt",
			"warnings");
		assertThat(json).isEqualTo(
			"{\"id\":\"" + PROFILE_ID + "\",\"imageId\":\"" + IMAGE_ID + "\",\"revision\":1,"
				+ "\"width\":8,\"height\":6,\"mediaType\":\"image/jpeg\","
				+ "\"displayUrl\":\"" + SIGNED_URL + "\",\"displayExpiresAt\":\"2026-08-23T12:00:00Z\","
				+ "\"warnings\":[]}");
		assertThat(json).doesNotContain("bucket", "storageKey", "objectKey", "signedUrl");
	}

	@Test
	void avatarResourceResponseSerializesPngAndWarnings() {
		AvatarResourceResponse response = new AvatarResourceResponse(
			PROFILE_ID,
			IMAGE_ID,
			2,
			1024,
			1536,
			"image/png",
			SIGNED_URL,
			EXPIRES_AT,
			List.of("borderline-blur"));

		String json = MAPPER.writeValueAsString(response);

		assertThat(json).isEqualTo(
			"{\"id\":\"" + PROFILE_ID + "\",\"imageId\":\"" + IMAGE_ID + "\",\"revision\":2,"
				+ "\"width\":1024,\"height\":1536,\"mediaType\":\"image/png\","
				+ "\"displayUrl\":\"" + SIGNED_URL + "\",\"displayExpiresAt\":\"2026-08-23T12:00:00Z\","
				+ "\"warnings\":[\"borderline-blur\"]}");
	}

	@Test
	void avatarResourceResponseToStringOmitsDisplayUrlAndSignedMaterial() {
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
		assertThat(response.toString()).doesNotContain("redact-me-token");
		assertThat(response.toString()).doesNotContain("displayUrl");
		assertThat(response.toString()).doesNotContain("bucket", "objectKey", "storageKey", "signedUrl");
	}

	@Test
	void avatarResourceResponseCopiesWarningsAndRejectsInvalidFields() {
		List<String> warnings = new ArrayList<>();
		warnings.add("borderline-dark");
		AvatarResourceResponse response = new AvatarResourceResponse(
			PROFILE_ID,
			IMAGE_ID,
			1,
			8,
			6,
			"image/jpeg",
			SIGNED_URL,
			EXPIRES_AT,
			warnings);

		warnings.add("injected");
		assertThat(response.warnings()).containsExactly("borderline-dark");
		assertThatThrownBy(() -> response.warnings().add("x")).isInstanceOf(UnsupportedOperationException.class);

		assertThatThrownBy(() -> resource(null, IMAGE_ID, 1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource("  ", IMAGE_ID, 1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, null, 1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, "  ", 1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 0, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, -1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 0, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, -1, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 0, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, -1, "image/jpeg", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, null, SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "image/webp", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "IMAGE/JPEG", SIGNED_URL, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "image/jpeg", null, EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "image/jpeg", "  ", EXPIRES_AT, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "image/jpeg", SIGNED_URL, null, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> resource(PROFILE_ID, IMAGE_ID, 1, 8, 6, "image/jpeg", SIGNED_URL, EXPIRES_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void avatarProblemResponseHasCodeOnly() {
		assertThat(componentNames(AvatarProblemResponse.class)).containsExactly("code");
		assertThat(componentTypes(AvatarProblemResponse.class)).containsExactly(String.class);
		assertThat(componentNames(AvatarProblemResponse.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl", "message", "detail");
	}

	@Test
	void avatarProblemResponseSerializesExactJson() {
		String json = MAPPER.writeValueAsString(new AvatarProblemResponse("AVATAR_NOT_FOUND"));

		assertThat(json).isEqualTo("{\"code\":\"AVATAR_NOT_FOUND\"}");
	}

	@Test
	void avatarProblemResponseAcceptsOnlyAllowedCodes() {
		assertThat(ALLOWED_CODES).containsExactly(
			Arrays.stream(AvatarProblemCode.values()).map(Enum::name).toArray(String[]::new));
		for (String code : ALLOWED_CODES) {
			AvatarProblemResponse response = new AvatarProblemResponse(code);
			assertThat(response.code()).isEqualTo(code);
			assertThat(MAPPER.writeValueAsString(response)).isEqualTo("{\"code\":\"" + code + "\"}");
		}

		assertThatThrownBy(() -> new AvatarProblemResponse(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("   "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("AVATAR_UNKNOWN"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("avatar_not_found"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("NOT_FOUND"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("LOW_LIGHT"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AvatarProblemResponse("FULL_BODY_NOT_VISIBLE"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	void avatarControllerMapsEveryProblemCodeToFrozenStatusAndCodeOnlyJson() {
		Map<AvatarProblemCode, HttpStatus> statuses = Map.ofEntries(
			Map.entry(AvatarProblemCode.AVATAR_UNREADABLE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_UNSUPPORTED_FORMAT, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_TYPE_MISMATCH, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_RESOLUTION_INSUFFICIENT, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_BYTES_EXCEEDED, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_DIMENSIONS_EXCEEDED, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_PIXELS_EXCEEDED, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_ASPECT_RATIO, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_TOO_BLURRED, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_TOO_DARK, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_CONFLICT, HttpStatus.CONFLICT),
			Map.entry(AvatarProblemCode.AVATAR_STALE_REVISION, HttpStatus.PRECONDITION_FAILED),
			Map.entry(AvatarProblemCode.AVATAR_NOT_FOUND, HttpStatus.NOT_FOUND),
			Map.entry(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE, HttpStatus.BAD_REQUEST),
			Map.entry(AvatarProblemCode.AVATAR_MEDIA_MISSING, HttpStatus.SERVICE_UNAVAILABLE),
			Map.entry(AvatarProblemCode.AVATAR_PERSON_NOT_SINGLE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_FACE_NOT_VISIBLE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_BODY_NOT_VISIBLE, HttpStatus.UNPROCESSABLE_CONTENT),
			Map.entry(AvatarProblemCode.AVATAR_PREFLIGHT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE));
		ObjectProvider<AvatarWorkflow> workflows = mock(ObjectProvider.class);
		ObjectProvider<ObjectStore> mediaStores = mock(ObjectProvider.class);
		AvatarsController controller = new AvatarsController(workflows, mediaStores);

		assertThat(statuses).hasSize(AvatarProblemCode.values().length);
		for (AvatarProblemCode code : AvatarProblemCode.values()) {
			ResponseEntity<AvatarProblemResponse> response = controller.avatarProblem(new AvatarException(code));

			assertThat(response.getStatusCode()).isEqualTo(statuses.get(code));
			assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
			assertThat(MAPPER.writeValueAsString(response.getBody()))
				.isEqualTo("{\"code\":\"" + code.name() + "\"}");
		}
	}

	@Test
	void avatarProblemResponseToStringContainsOnlyTheCode() {
		AvatarProblemResponse response = new AvatarProblemResponse("AVATAR_NOT_FOUND");

		assertThat(response.toString()).isEqualTo("AvatarProblemResponse[code=AVATAR_NOT_FOUND]");
		assertThat(response.toString()).doesNotContain(SIGNED_URL, "displayUrl", "bucket", "objectKey", "storageKey");
	}

	private static AvatarResourceResponse resource(
		String id,
		String imageId,
		int revision,
		int width,
		int height,
		String mediaType,
		String displayUrl,
		Instant displayExpiresAt,
		List<String> warnings) {
		return new AvatarResourceResponse(
			id,
			imageId,
			revision,
			width,
			height,
			mediaType,
			displayUrl,
			displayExpiresAt,
			warnings);
	}

	private static List<String> componentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
	}

	private static List<Class<?>> componentTypes(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toList();
	}
}
