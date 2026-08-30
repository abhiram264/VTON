package com.aifashionvault.generation;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;

public record QualityResult(
	QualityResultId id,
	GenerationAttemptId generationAttemptId,
	QualityDecision decision,
	java.math.BigDecimal identityPreservation,
	java.math.BigDecimal garmentFidelity,
	java.math.BigDecimal colorFidelity,
	Boolean anatomyValid,
	Boolean artifactDetected,
	java.time.Instant createdAt,
	String qualityPolicyVersion,
	String reasonCode) {

	public QualityResult(
		QualityResultId id,
		GenerationAttemptId generationAttemptId,
		QualityDecision decision,
		java.math.BigDecimal identityPreservation,
		java.math.BigDecimal garmentFidelity,
		java.math.BigDecimal colorFidelity,
		Boolean anatomyValid,
		Boolean artifactDetected,
		java.time.Instant createdAt) {
		this(
			id,
			generationAttemptId,
			decision,
			identityPreservation,
			garmentFidelity,
			colorFidelity,
			anatomyValid,
			artifactDetected,
			createdAt,
			QualityGate.BASELINE_POLICY_VERSION,
			null);
	}

	public QualityResult(
		QualityResultId id,
		GenerationAttemptId generationAttemptId,
		QualityDecision decision,
		java.math.BigDecimal identityPreservation,
		java.math.BigDecimal garmentFidelity,
		java.math.BigDecimal colorFidelity,
		Boolean anatomyValid,
		Boolean artifactDetected,
		java.time.Instant createdAt,
		String qualityPolicyVersion) {
		this(
			id,
			generationAttemptId,
			decision,
			identityPreservation,
			garmentFidelity,
			colorFidelity,
			anatomyValid,
			artifactDetected,
			createdAt,
			QualityGate.boundPolicyVersion(qualityPolicyVersion),
			null);
	}


	public QualityResult {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (generationAttemptId == null) {
			throw new IllegalArgumentException("generationAttemptId is required");
		}
		if (decision == null) {
			throw new IllegalArgumentException("decision is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (reasonCode != null) {
			reasonCode = reasonCode.strip().toUpperCase(java.util.Locale.ROOT);
			if (reasonCode.isEmpty()) {
				reasonCode = null;
			}
			else if (!reasonCode.matches("[A-Z0-9][A-Z0-9_.:-]{0,63}")) {
				throw new IllegalArgumentException("reasonCode must be a normalized application-owned code");
			}
		}
	}

	public QualityResult bindPolicyVersion(String alreadyBound) {
		String bound = QualityGate.boundPolicyVersion(alreadyBound);
		if (!bound.equals(qualityPolicyVersion)) {
			throw new IllegalArgumentException("QualityPolicyVersion is already bound");
		}
		return this;
	}

}
