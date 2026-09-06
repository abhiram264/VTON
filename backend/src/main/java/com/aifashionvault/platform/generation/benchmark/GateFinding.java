package com.aifashionvault.platform.generation.benchmark;

/**
 * A single scalar finding produced by the benchmark release gate (Plan §52, ADR-015).
 */
public record GateFinding(
		String rule,
		String dimension,
		String category,
		String severity,
		String baselineValue,
		String candidateValue,
		String detail) {

	public GateFinding {
		if (rule == null || rule.isBlank()) {
			throw new IllegalArgumentException("rule must not be blank");
		}
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("dimension must not be blank");
		}
		if (category == null || category.isBlank()) {
			throw new IllegalArgumentException("category must not be blank");
		}
		if (severity == null || severity.isBlank()) {
			throw new IllegalArgumentException("severity must not be blank");
		}
		if (!"block".equals(severity) && !"review".equals(severity)) {
			throw new IllegalArgumentException("severity must be 'block' or 'review', got: " + severity);
		}
		if (baselineValue == null || baselineValue.isBlank()) {
			throw new IllegalArgumentException("baselineValue must not be blank");
		}
		if (candidateValue == null || candidateValue.isBlank()) {
			throw new IllegalArgumentException("candidateValue must not be blank");
		}
		if (detail == null || detail.isBlank()) {
			throw new IllegalArgumentException("detail must not be blank");
		}
	}
}
