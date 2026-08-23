package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresignedAccessTest {

	private static final ObjectRef OBJECT = new ObjectRef(StorageBoundary.TEMP, "temp/u/uploads/id");
	private static final URI SIGNED_URI = URI.create(
		"https://configured-temp.s3.amazonaws.com/temp/u/uploads/id?X-Amz-Signature=redact-me-token");
	private static final Instant EXPIRES_AT = Instant.parse("2026-08-23T12:00:00Z");

	@Test
	void toStringOmitsUriAndUrlMaterial() {
		PresignedAccess access = new PresignedAccess(
			OBJECT,
			"PUT",
			SIGNED_URI,
			Map.of("content-type", "image/jpeg"),
			EXPIRES_AT);

		assertThat(access.uri()).isEqualTo(SIGNED_URI);
		assertThat(access.toString()).doesNotContain(SIGNED_URI.toString());
		assertThat(access.toString()).doesNotContain("redact-me-token");
		assertThat(access.toString()).doesNotContain("https://");
		assertThat(access.toString()).contains("signedHeaders=");
	}

	@Test
	void signedHeadersAreCopiedUnmodifiableAndEmptyIsAllowed() {
		Map<String, String> headers = new HashMap<>();
		headers.put("content-type", "image/png");
		PresignedAccess access = new PresignedAccess(OBJECT, "GET", SIGNED_URI, headers, EXPIRES_AT);

		headers.put("x-injected", "nope");
		assertThat(access.signedHeaders()).containsExactlyEntriesOf(Map.of("content-type", "image/png"));
		assertThatThrownBy(() -> access.signedHeaders().put("x", "y"))
			.isInstanceOf(UnsupportedOperationException.class);

		PresignedAccess empty = new PresignedAccess(OBJECT, "PUT", SIGNED_URI, Map.of(), EXPIRES_AT);
		assertThat(empty.signedHeaders()).isEmpty();
	}

	@Test
	void rejectsNullSignedHeadersAndOtherRequiredFields() {
		Map<String, String> headers = Map.of("host", "example.invalid");

		assertThatThrownBy(() -> new PresignedAccess(null, "PUT", SIGNED_URI, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PresignedAccess(OBJECT, null, SIGNED_URI, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PresignedAccess(OBJECT, "  ", SIGNED_URI, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PresignedAccess(OBJECT, "PUT", null, headers, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PresignedAccess(OBJECT, "PUT", SIGNED_URI, null, EXPIRES_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PresignedAccess(OBJECT, "PUT", SIGNED_URI, headers, null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
