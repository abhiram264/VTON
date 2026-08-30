package com.aifashionvault.generation;

import com.aifashionvault.shared.GenerationAttemptId;

/**
 * Product-owned acceptance boundary for generated candidates.
 *
 * <p>Evaluator infrastructure unavailability is represented by
 * {@link QualityEvaluationOutcome.Unavailable} and is never represented by or
 * persisted as a {@link QualityResult}. Product decisions remain explicit
 * {@code PASS}, {@code RETRY}, or {@code FAIL} results.
 *
 * <p>All attempts of a job are evaluated under the same frozen quality-policy
 * version, so retry outcomes remain comparable and reproducible (Q52).
 */
public interface QualityGate {

	String BASELINE_POLICY_VERSION = "baseline-v1";
	String QUALITY_V2_POLICY_VERSION = "quality-v2";

	/**
	 * Evaluates candidate-only bytes with the baseline-v1 policy.
	 *
	 * <p>This compatibility method remains the candidate-only baseline seam.
	 */
	QualityResult evaluate(GenerationAttemptId attemptId, byte[] applicationOwnedImage);

	default String policyVersion() {
		return BASELINE_POLICY_VERSION;
	}

	static String boundPolicyVersion(String alreadyBound) {
		if (alreadyBound == null || alreadyBound.isBlank()) {
			return BASELINE_POLICY_VERSION;
		}
		return alreadyBound;
	}

	/**
	 * Dispatches an evaluation request using its already-bound policy version.
	 *
	 * <p>The baseline policy delegates to the existing candidate-only evaluator.
	 * The quality-v2 port default fails closed until a quality-v2 implementation
	 * overrides this method.
	 */
	default QualityEvaluationOutcome evaluate(QualityEvaluationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request is required");
		}
		String bound = boundPolicyVersion(request.boundPolicyVersion());
		if (!BASELINE_POLICY_VERSION.equals(bound) && !QUALITY_V2_POLICY_VERSION.equals(bound)) {
			return new QualityEvaluationOutcome.Unavailable("QUALITY_POLICY_UNSUPPORTED");
		}
		if (BASELINE_POLICY_VERSION.equals(bound)) {
			return new QualityEvaluationOutcome.Completed(
				evaluate(request.attemptId(), request.candidateBytes(), bound));
		}
		return new QualityEvaluationOutcome.Unavailable("QUALITY_POLICY_UNSUPPORTED");
	}

	default QualityResult evaluate(
		GenerationAttemptId attemptId,
		byte[] applicationOwnedImage,
		String alreadyBoundPolicyVersion) {
		String bound = boundPolicyVersion(alreadyBoundPolicyVersion);
		if (!policyVersion().equals(bound)) {
			throw new IllegalArgumentException("bound QualityPolicyVersion cannot be reinterpreted");
		}
		return evaluate(attemptId, applicationOwnedImage).bindPolicyVersion(bound);
	}
}
