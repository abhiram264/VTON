package com.aifashionvault.normalization;

public interface ProductNormalizer {

	NormalizedProduct normalize(
		String category,
		String subcategory,
		String brand,
		String primaryColor,
		String pattern,
		double confidence);
}
