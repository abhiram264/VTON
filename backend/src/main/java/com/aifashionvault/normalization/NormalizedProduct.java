package com.aifashionvault.normalization;

import java.util.Objects;

public record NormalizedProduct(
		ProductCategory category,
		String subcategory,
		String brand,
		String primaryColor,
		String pattern,
		double confidence) {

	public NormalizedProduct {
		Objects.requireNonNull(category, "category");
		Confidence.requireValid(confidence);
	}

	public boolean requiresCategoryCorrection() {
		return confidence < 0.70;
	}
}
