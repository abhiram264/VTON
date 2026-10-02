package com.aifashionvault.platform.web;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import com.aifashionvault.normalization.ProductCategory;

public record VaultItemResponse(String id, String sourceReferenceId, String sourceUrl, String name, String brand,
	ProductCategory category, String subcategory, String color, String pattern, JsonNode attributes, Instant createdAt) {}
