package com.aifashionvault.importer;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareImportTest {

	private static final ShareImportId ID = new ShareImportId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant CREATED_AT = Instant.parse("2026-01-15T12:00:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-15T12:30:00Z");
	private static final String HTTPS_SOURCE = "https://example.com/item";

	@Test
	void userIdIsRequired() {
		assertThatThrownBy(() -> new ShareImport(
			ID,
			null,
			"url",
			HTTPS_SOURCE,
			null,
			"pending",
			CREATED_AT,
			UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sourceUrlMayBeHttpsShareLocator() {
		ShareImport shareImport = new ShareImport(
			ID,
			USER_ID,
			"url",
			HTTPS_SOURCE,
			null,
			"pending",
			CREATED_AT,
			UPDATED_AT);

		assertThat(shareImport.sourceUrl()).isEqualTo(HTTPS_SOURCE);
	}

	@Test
	void storageKeyMayBeNull() {
		ShareImport shareImport = new ShareImport(
			ID,
			USER_ID,
			"url",
			HTTPS_SOURCE,
			null,
			"pending",
			CREATED_AT,
			UPDATED_AT);

		assertThat(shareImport.storageKey()).isNull();
	}

	@Test
	void hasNoBinaryPayloadFields() {
		assertThat(ShareImport.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl", "image_url", "base64", "base64Image", "bytes", "payload");
		assertThat(ShareImport.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(byte[].class);
	}

	@Test
	void requiredFieldsRejectNullAndBlank() {
		StorageKey storageKey = new StorageKey("users/u/imports/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.jpg");

		assertThatThrownBy(() -> new ShareImport(null, USER_ID, "url", HTTPS_SOURCE, storageKey, "pending", CREATED_AT, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, null, HTTPS_SOURCE, storageKey, "pending", CREATED_AT, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "   ", HTTPS_SOURCE, storageKey, "pending", CREATED_AT, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "url", HTTPS_SOURCE, storageKey, null, CREATED_AT, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "url", HTTPS_SOURCE, storageKey, "", CREATED_AT, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "url", HTTPS_SOURCE, storageKey, "pending", null, UPDATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "url", HTTPS_SOURCE, storageKey, "pending", CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
