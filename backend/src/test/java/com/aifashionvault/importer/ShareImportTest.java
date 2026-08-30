package com.aifashionvault.importer;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareImportTest {

	private static final ShareImportId ID = new ShareImportId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId USER_ID = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final URI SOURCE_URL = URI.create("https://example.test/item");
	private static final StorageKey STORAGE_KEY = new StorageKey("imports/private/resolved-object");
	private static final String FINGERPRINT = "URL|/item|";
	private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");

	@Test
	void imageImportRejectsSourceUrl() {
		ShareImport shareImport = new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.IMAGE, null, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT);
		assertThat(shareImport.kind()).isEqualTo(ShareImportKind.IMAGE);
		assertThat(shareImport.sourceUrl()).isNull();

		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.IMAGE, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void urlImportRequiresHttpSourceUrl() {
		ShareImport shareImport = new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT);
		assertThat(shareImport.sourceUrl()).isEqualTo(SOURCE_URL);

		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, URI.create("file://example.test/item"), null, ShareImportState.RESOLVING,
			1, CREATED_AT, UUID.randomUUID(), null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, URI.create("https://user:secret@example.test/item"), null, ShareImportState.RESOLVING,
			1, CREATED_AT, UUID.randomUUID(), null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void readyImportRequiresResolvedStorageKey() {
		ShareImport shareImport = new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, STORAGE_KEY, ShareImportState.READY, 1, null, null,
			null, null, null, CREATED_AT, CREATED_AT);
		assertThat(shareImport.storageKey()).isEqualTo(STORAGE_KEY);

		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.READY, 1, null, null,
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void needsImageCarriesResolutionFailureReason() {
		ShareImport shareImport = new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.NEEDS_IMAGE, 1, null, null,
			ResolutionReason.URL_RESOLUTION_FAILED, null, null, CREATED_AT, CREATED_AT);
		assertThat(shareImport.state()).isEqualTo(ShareImportState.NEEDS_IMAGE);
		assertThat(shareImport.terminalReason()).isEqualTo(ResolutionReason.URL_RESOLUTION_FAILED);

		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.NEEDS_IMAGE, 1, null, null,
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void resolvingImportMustNotCarryTerminalReason() {
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			ResolutionReason.INVALID_SOURCE, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void attemptTokenRequiresLeaseTimestamp() {
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, null, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void idempotencyKeyMustBePrintableAsciiOfBoundedLength() {
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "short-key", FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, "key\u00e9-with-non-ascii-value!0123", FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiredComponentsAreGuarded() {
		assertThatThrownBy(() -> new ShareImport(null, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ShareImport(ID, null, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, null, FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), null,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, -1, CREATED_AT, UUID.randomUUID(),
			null, null, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, null, null, null, CREATED_AT))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void sourceMetadataIsBounded() {
		String oversized = "x".repeat(ImportResolverLimits.MAX_SOURCE_METADATA_LENGTH + 1);
		assertThatThrownBy(() -> new ShareImport(ID, USER_ID, validKey(), FINGERPRINT,
			ShareImportKind.URL, SOURCE_URL, null, ShareImportState.RESOLVING, 1, CREATED_AT, UUID.randomUUID(),
			null, oversized, null, CREATED_AT, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hasNoBinaryPayloadFields() {
		Set<String> forbiddenNames = Set.of("imageurl", "image_url", "base64", "base64image", "bytes", "payload");
		for (RecordComponent component : ShareImport.class.getRecordComponents()) {
			assertThat(forbiddenNames).doesNotContain(component.getName().toLowerCase(Locale.ROOT));
			assertThat(component.getType()).isNotEqualTo(byte[].class);
		}
	}

	private static String validKey() {
		return "idempotency-key-0123456789";
	}
}
