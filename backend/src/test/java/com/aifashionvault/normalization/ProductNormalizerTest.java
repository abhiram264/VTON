package com.aifashionvault.normalization;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductNormalizerTest {

	private final ProductNormalizer normalizer = new DeterministicProductNormalizer();

	@Test
	void preservesEveryExactTaxonomyValue() {
		for (ProductCategory category : ProductCategory.values()) {
			assertThat(normalize(category.name(), 1.0).category()).isEqualTo(category);
		}
	}

	@Test
	void canonicalizesCategoryCaseWhitespaceAndSeparators() {
		assertThat(normalize("  one piece  ", 0.9).category()).isEqualTo(ProductCategory.ONE_PIECE);
		assertThat(normalize("ethnic-full body", 0.9).category()).isEqualTo(ProductCategory.ETHNIC_FULL_BODY);
		assertThat(normalize("jewellery", 0.9).category()).isEqualTo(ProductCategory.JEWELLERY);
	}

	@Test
	void unsupportedMissingAndBlankCategoriesAreUnknown() {
		assertThat(normalize(null, 0.4).category()).isEqualTo(ProductCategory.UNKNOWN);
		assertThat(normalize("   ", 0.4).category()).isEqualTo(ProductCategory.UNKNOWN);
		assertThat(normalize("shirt", 0.4).category()).isEqualTo(ProductCategory.UNKNOWN);
	}

	@Test
	void normalizesPlanExampleExactly() {
		NormalizedProduct result = normalizer.normalize("top", " SHIRT ", " Rare Rabbit ",
			" black ", " solid ", 0.91);

		assertThat(result).isEqualTo(new NormalizedProduct(
			ProductCategory.TOP, "SHIRT", "Rare Rabbit", "BLACK", "SOLID", 0.91));
		assertThat(result.requiresCategoryCorrection()).isFalse();
	}

	@Test
	void blankOptionalValuesAreAbsentAndFilterValuesAreCanonical() {
		NormalizedProduct result = normalizer.normalize("bag", "  ", "\t", " rose-gold ", " color block ", 0.8);

		assertThat(result.subcategory()).isNull();
		assertThat(result.brand()).isNull();
		assertThat(result.primaryColor()).isEqualTo("ROSE_GOLD");
		assertThat(result.pattern()).isEqualTo("COLOR_BLOCK");
	}

	@Test
	void correctionThresholdIsStrictlyBelowPointSeven() {
		double below = Math.nextDown(0.70);
		assertThat(normalize("top", 0.0).requiresCategoryCorrection()).isTrue();
		assertThat(normalize("top", below).requiresCategoryCorrection()).isTrue();
		assertThat(normalize("top", 0.70).requiresCategoryCorrection()).isFalse();
		assertThat(normalize("top", 1.0).requiresCategoryCorrection()).isFalse();
		assertThat(normalize("top", below).confidence()).isEqualTo(below);
	}

	@Test
	void rejectsNonFiniteAndOutOfRangeConfidence() {
		for (double invalid : new double[] {-0.01, 1.01, Double.NaN,
			Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertThatThrownBy(() -> normalize("top", invalid))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void usesLocaleIndependentAsciiCanonicalization() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertThat(normalize("one-piece", 0.8).category()).isEqualTo(ProductCategory.ONE_PIECE);
		}
		finally {
			Locale.setDefault(original);
		}
	}

	private NormalizedProduct normalize(String category, double confidence) {
		return normalizer.normalize(category, null, null, null, null, confidence);
	}
}
