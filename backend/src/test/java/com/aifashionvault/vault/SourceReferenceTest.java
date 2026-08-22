package com.aifashionvault.vault;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.SourceReferenceId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceReferenceTest {

	private static final SourceReferenceId ID = new SourceReferenceId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

	@Test
	void hasNoUserIdComponent() {
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("userId", "user_id");
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(UserId.class);
	}

	@Test
	void componentShapeMatchesSourceReferenceTable() {
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "url", "retailer", "rawMetadata", "createdAt");
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(SourceReferenceId.class, String.class, String.class, String.class, Instant.class);
	}

	@Test
	void hasNoImageUrlOrByteArrayFields() {
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.doesNotContain("imageUrl", "image_url");
		assertThat(SourceReference.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.doesNotContain(byte[].class);
	}

	@Test
	void constructsWithNullableMetadata() {
		SourceReference reference = new SourceReference(ID, null, null, null, CREATED_AT);

		assertThat(reference.id()).isEqualTo(ID);
		assertThat(reference.url()).isNull();
		assertThat(reference.retailer()).isNull();
		assertThat(reference.rawMetadata()).isNull();
		assertThat(reference.createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void constructsWithAllFields() {
		SourceReference reference = new SourceReference(
			ID, "https://shop.example/item", "Example Shop", "{\"sku\":\"1\"}", CREATED_AT);

		assertThat(reference.url()).isEqualTo("https://shop.example/item");
		assertThat(reference.retailer()).isEqualTo("Example Shop");
		assertThat(reference.rawMetadata()).isEqualTo("{\"sku\":\"1\"}");
	}

	@Test
	void idIsRequired() {
		assertThatThrownBy(() -> new SourceReference(null, "https://shop.example/item", "Example Shop", "{}", CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void createdAtIsRequired() {
		assertThatThrownBy(() -> new SourceReference(ID, "https://shop.example/item", "Example Shop", "{}", null))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
