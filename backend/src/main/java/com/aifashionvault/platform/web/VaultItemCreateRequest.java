package com.aifashionvault.platform.web;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.aifashionvault.normalization.ProductCategory;

@JsonIgnoreProperties(ignoreUnknown = false)
public record VaultItemCreateRequest(String uploadId, String sourceReferenceId, String name, String brand,
	ProductCategory category, String subcategory, String color, String pattern, JsonNode attributes) {}
