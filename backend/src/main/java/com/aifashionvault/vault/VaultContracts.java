package com.aifashionvault.vault;

import java.time.LocalDate;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import com.aifashionvault.normalization.ProductCategory;

public final class VaultContracts {
	private VaultContracts() {}

	public record Filters(ProductCategory category, UUID collection, String brand, String color,
		String search, String source, LocalDate createdDate) {}

	public enum DeleteResult { DELETED, NOT_FOUND, IN_USE }

	public enum ProblemCode {
		VAULT_ITEM_NOT_FOUND, VAULT_SOURCE_NOT_FOUND, VAULT_UPLOAD_NOT_CLAIMABLE,
		VAULT_ITEM_IN_USE, VAULT_INVALID_REQUEST, VAULT_MEDIA_UNAVAILABLE
	}

	public record Patch(
		JsonNullable<String> name, JsonNullable<String> brand, JsonNullable<ProductCategory> category,
		JsonNullable<String> subcategory, JsonNullable<String> color, JsonNullable<String> pattern,
		JsonNullable<JsonNode> attributes) {
		public boolean empty() {
			return name.undefined() && brand.undefined() && category.undefined() && subcategory.undefined()
				&& color.undefined() && pattern.undefined() && attributes.undefined();
		}
	}

	public record JsonNullable<T>(boolean defined, T value) {
		public static <T> JsonNullable<T> undefinedValue() { return new JsonNullable<>(false, null); }
		public static <T> JsonNullable<T> of(T value) { return new JsonNullable<>(true, value); }
		public boolean undefined() { return !defined; }
	}
}
