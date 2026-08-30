package com.aifashionvault.platform.generation;

import java.math.BigDecimal;

/**
 * Coarse, application-owned evidence emitted by a quality-v2 vision evaluator.
 *
 * <p>The three numeric measurements are acceptability diagnostics only, never
 * attractiveness or beauty scores. A completed evaluator must provide finite
 * decimal values in the closed interval {@code [0, 1]}; malformed evidence is
 * rejected before it can become a product result.
 */
public record QualityVisionEvidence(
	int personCount,
	boolean bodySufficientlyVisible,
	BigDecimal identityPreservation,
	BigDecimal garmentFidelity,
	BigDecimal colorFidelity,
	boolean anatomyValid,
	boolean artifactDetected,
	boolean previousItemsPreserved) {

	public QualityVisionEvidence {
		if (personCount < 0) {
			throw new IllegalArgumentException("personCount must be non-negative");
		}
		identityPreservation = requireScore(identityPreservation, "identityPreservation");
		garmentFidelity = requireScore(garmentFidelity, "garmentFidelity");
		colorFidelity = requireScore(colorFidelity, "colorFidelity");
	}

	private static BigDecimal requireScore(BigDecimal score, String name) {
		if (score == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(name + " must be in [0,1]");
		}
		return score;
	}
}
