package com.aifashionvault.vault;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.aifashionvault.normalization.ProductCategory;
import com.aifashionvault.shared.SourceReferenceId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultItemTest {

	private static final VaultItemId ID = new VaultItemId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final UserId USER_ID = new UserId(
		UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final StorageKey STORAGE_KEY = new StorageKey(
		"users/11111111-1111-1111-1111-111111111111/vault/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.jpg");
	private static final SourceReferenceId SOURCE_REFERENCE_ID = new SourceReferenceId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

	@Test
	void componentShapeIsUserOwnedStorageKeyOnly() {
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"userId",
				"storageKey",
				"sourceReferenceId",
				"sourceUrl",
				"name",
				"brand",
				"category",
				"subcategory",
				"color",
				"pattern",
				"attributes",
				"createdAt");
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				VaultItemId.class,
				UserId.class,
				StorageKey.class,
				SourceReferenceId.class,
				String.class,
				String.class,
				String.class,
				ProductCategory.class,
				String.class,
				String.class,
				String.class,
				JsonNode.class,
				Instant.class);
	}

	@Test
	void hasNoImageUrlOrByteArrayFields() {
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl", "image_url");
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(byte[].class);
	}

	@Test
	void hasNoOrgOrTenantComponents() {
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("orgId", "org_id", "tenantId", "tenant_id");
	}

	@Test
	void sourceReferenceIdMayBeNull() {
		VaultItem item = item(null);

		assertThat(item.sourceReferenceId()).isNull();
		assertThat(item.userId()).isEqualTo(USER_ID);
		assertThat(item.storageKey()).isEqualTo(STORAGE_KEY);
	}

	@Test
	void sourceReferenceIdIsTypedIdNotEmbeddedSource() {
		VaultItem item = item(SOURCE_REFERENCE_ID);

		assertThat(item.sourceReferenceId()).isEqualTo(SOURCE_REFERENCE_ID);
		assertThat(VaultItem.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(SourceReference.class);
	}

	@Test
	void requiresUserId() {
		assertThatThrownBy(() -> new VaultItem(
			ID, null, STORAGE_KEY, SOURCE_REFERENCE_ID, null, "Shirt", "Brand", ProductCategory.TOP, null, "black", null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresStorageKey() {
		assertThatThrownBy(() -> new VaultItem(
			ID, USER_ID, null, SOURCE_REFERENCE_ID, null, "Shirt", "Brand", ProductCategory.TOP, null, "black", null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresIdAndCreatedAt() {
		assertThatThrownBy(() -> new VaultItem(
			null, USER_ID, STORAGE_KEY, null, null, null, null, null, null, null, null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new VaultItem(
			ID, USER_ID, STORAGE_KEY, null, null, null, null, null, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void optionalCatalogFieldsMayBeNull() {
		VaultItem item = new VaultItem(
			ID, USER_ID, STORAGE_KEY, null, null, null, null, null, null, null, null, null, CREATED_AT);

		assertThat(item.name()).isNull();
		assertThat(item.brand()).isNull();
		assertThat(item.category()).isNull();
		assertThat(item.subcategory()).isNull();
		assertThat(item.color()).isNull();
		assertThat(item.pattern()).isNull();
		assertThat(item.attributes()).isNull();
	}

	private static VaultItem item(SourceReferenceId sourceReferenceId) {
		return new VaultItem(
			ID, USER_ID, STORAGE_KEY, sourceReferenceId, null, "Shirt", "Brand", ProductCategory.TOP, "shirt", "black", "solid", new ObjectMapper().readTree("{}"), CREATED_AT);
	}
}
