package com.aifashionvault.normalization;

import java.util.Locale;

public final class DeterministicProductNormalizer implements ProductNormalizer {

	@Override
	public NormalizedProduct normalize(String category, String subcategory, String brand,
			String primaryColor, String pattern, double confidence) {
		Confidence.requireValid(confidence);
		return new NormalizedProduct(
			normalizeCategory(category),
			trimToNull(subcategory),
			trimToNull(brand),
			canonicalOptional(primaryColor),
			canonicalOptional(pattern),
			confidence);
	}

	private static ProductCategory normalizeCategory(String value) {
		String canonical = canonicalOptional(value);
		if (canonical == null) {
			return ProductCategory.UNKNOWN;
		}
		try {
			return ProductCategory.valueOf(canonical);
		}
		catch (IllegalArgumentException ignored) {
			return ProductCategory.UNKNOWN;
		}
	}

	private static String canonicalOptional(String value) {
		String trimmed = trimToNull(value);
		return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
