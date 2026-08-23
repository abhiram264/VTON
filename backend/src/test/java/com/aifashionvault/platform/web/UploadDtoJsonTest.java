package com.aifashionvault.platform.web;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.objectstore.ImageMediaPolicy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadDtoJsonTest {

	private static final String UPLOAD_ID = "22222222-2222-2222-2222-222222222222";

	private static final String SIGNED_URL = "https://example.invalid/staging/put?X-Amz-Signature=redact-me-token";

	private static final Instant EXPIRES_AT = Instant.parse("2026-08-23T12:00:00Z");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void createUploadRequestHasContentTypeAndContentLengthOnly() {
		assertThat(componentNames(CreateUploadRequest.class)).containsExactly("contentType", "contentLength");
		assertThat(componentTypes(CreateUploadRequest.class)).containsExactly(String.class, long.class);
		assertThat(componentNames(CreateUploadRequest.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl");
	}

	@Test
	void createUploadRequestSerializesCanonicalFieldNames() {
		String json = MAPPER.writeValueAsString(new CreateUploadRequest("image/jpeg", 1024L));

		assertThat(json).isEqualTo("{\"contentType\":\"image/jpeg\",\"contentLength\":1024}");
	}

	@Test
	void createUploadRequestStoresNormalizedContentType() {
		CreateUploadRequest request = new CreateUploadRequest("IMAGE/JPEG", 1024L);

		assertThat(request.contentType()).isEqualTo("image/jpeg");
		assertThat(request.contentLength()).isEqualTo(1024L);
	}

	@Test
	void createUploadRequestRejectsUnsupportedMimeViaImageMediaPolicy() {
		assertThatThrownBy(() -> new CreateUploadRequest("image/webp", 1024L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("unsupported content type");
		assertThatThrownBy(() -> new CreateUploadRequest("image/gif", 1024L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("unsupported content type");
	}

	@Test
	void createUploadRequestRejectsOutOfRangeLengthViaImageMediaPolicy() {
		assertThatThrownBy(() -> new CreateUploadRequest("image/jpeg", 0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("content length is out of range");
		assertThatThrownBy(() -> new CreateUploadRequest("image/jpeg", -1L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("content length is out of range");
		assertThatThrownBy(() -> new CreateUploadRequest("image/jpeg", ImageMediaPolicy.MAX_BYTES + 1L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("content length is out of range");
	}

	@Test
	void createUploadResponseHasTicketFieldNamesOnly() {
		assertThat(componentNames(CreateUploadResponse.class))
			.containsExactly("id", "method", "url", "headers", "expiresAt");
		assertThat(componentNames(CreateUploadResponse.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl");
	}

	@Test
	void createUploadResponseSerializesTicketFieldsWithoutStorageKeys() {
		Map<String, String> headers = Map.of("content-type", "image/jpeg");
		CreateUploadResponse response = new CreateUploadResponse(UPLOAD_ID, "PUT", SIGNED_URL, headers, EXPIRES_AT);

		String json = MAPPER.writeValueAsString(response);
		JsonNode node = MAPPER.readTree(json);

		assertThat(node.propertyNames()).containsExactly("id", "method", "url", "headers", "expiresAt");
		assertThat(node.get("id").stringValue()).isEqualTo(UPLOAD_ID);
		assertThat(node.get("method").stringValue()).isEqualTo("PUT");
		assertThat(node.get("url").stringValue()).isEqualTo(SIGNED_URL);
		assertThat(node.get("headers").get("content-type").stringValue()).isEqualTo("image/jpeg");
		assertThat(node.get("expiresAt").isNull()).isFalse();
		assertThat(json).doesNotContain("bucket", "storageKey", "objectKey");
	}

	@Test
	void createUploadResponseToStringDoesNotContainUrl() {
		CreateUploadResponse response = new CreateUploadResponse(
			UPLOAD_ID,
			"PUT",
			SIGNED_URL,
			Map.of("host", "example.invalid"),
			EXPIRES_AT);

		assertThat(response.toString()).doesNotContain(SIGNED_URL);
		assertThat(response.toString()).doesNotContain("redact-me-token");
	}

	@Test
	void createUploadResponseCopiesHeadersAndRejectsNullOrBlankRequiredFields() {
		Map<String, String> headers = new HashMap<>();
		headers.put("host", "example.invalid");
		CreateUploadResponse response = new CreateUploadResponse(UPLOAD_ID, "PUT", SIGNED_URL, headers, EXPIRES_AT);

		headers.put("x-injected", "nope");
		assertThat(response.headers()).containsExactlyEntriesOf(Map.of("host", "example.invalid"));
		assertThatThrownBy(() -> response.headers().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);

		assertThatThrownBy(() -> new CreateUploadResponse(null, "PUT", SIGNED_URL, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse("  ", "PUT", SIGNED_URL, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, null, SIGNED_URL, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, "  ", SIGNED_URL, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, "PUT", null, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, "PUT", "  ", headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, "PUT", SIGNED_URL, null, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateUploadResponse(UPLOAD_ID, "PUT", SIGNED_URL, headers, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void completeUploadResponseSerializesReadyWithExactFieldNames() {
		CompleteUploadResponse response = new CompleteUploadResponse(
			UPLOAD_ID,
			"READY",
			"image/jpeg",
			12L,
			8,
			6);

		String json = MAPPER.writeValueAsString(response);

		assertThat(json).isEqualTo(
			"{\"id\":\"" + UPLOAD_ID + "\",\"status\":\"READY\",\"mediaType\":\"image/jpeg\","
				+ "\"contentLength\":12,\"width\":8,\"height\":6}");
		assertThat(componentNames(CompleteUploadResponse.class))
			.containsExactly("id", "status", "mediaType", "contentLength", "width", "height");
		assertThat(json).doesNotContain("bucket", "storageKey", "objectKey");
		assertThat(componentNames(CompleteUploadResponse.class))
			.doesNotContain("bucket", "objectKey", "storageKey", "key", "signedUrl");
	}

	@Test
	void completeUploadResponseRejectsStatusOtherThanReady() {
		assertThatThrownBy(() -> new CompleteUploadResponse(UPLOAD_ID, "PENDING", "image/jpeg", 12L, 8, 6))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompleteUploadResponse(UPLOAD_ID, "ready", "image/jpeg", 12L, 8, 6))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompleteUploadResponse(UPLOAD_ID, "COMPLETE", "image/jpeg", 12L, 8, 6))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static List<String> componentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
	}

	private static List<Class<?>> componentTypes(Class<?> type) {
		return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toList();
	}
}
