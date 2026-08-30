package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;

/**
 * Product-owned acceptance boundary for generated candidates.
 *
 * <p>Provider technical success is never sufficient for product success. Every
 * candidate must cross this gate before acceptance, private promotion, and
 * user-visible success (Q69, Q72).
 *
 * <p>The candidate-only overload is the frozen baseline-v1 evaluator. The
 * request overload dispatches to the policy bound to the attempt and evaluates
 * quality-v2 exclusively on application-owned bytes.
 */
public final class ApplicationOwnedQualityGate implements QualityGate {

	private static final int MIN_PASS_DIMENSION = 256;
	private static final BigDecimal MIN_IDENTITY_PRESERVATION = new BigDecimal("0.80");
	private static final BigDecimal MIN_GARMENT_FIDELITY = new BigDecimal("0.80");
	private static final BigDecimal MIN_COLOR_FIDELITY = new BigDecimal("0.85");
	private static final String INPUT_MEDIA_MISSING = "INPUT_MEDIA_MISSING";
	private static final String IMAGE_INVALID = "IMAGE_INVALID";
	private static final String IMAGE_TOO_SMALL = "IMAGE_TOO_SMALL";
	private static final String EVALUATOR_UNAVAILABLE = "EVALUATOR_UNAVAILABLE";
	private static final String QUALITY_POLICY_UNSUPPORTED = "QUALITY_POLICY_UNSUPPORTED";

	private final ImageInspector inspector;
	private final QualityVisionPort visionPort;
	private final Clock clock;

	public ApplicationOwnedQualityGate(ImageInspector inspector) {
		this(inspector, null, Clock.systemUTC());
	}

	public ApplicationOwnedQualityGate(ImageInspector inspector, Clock clock) {
		this(inspector, null, clock);
	}

	public ApplicationOwnedQualityGate(ImageInspector inspector, QualityVisionPort visionPort, Clock clock) {
		if (inspector == null) {
			throw new IllegalArgumentException("inspector is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.inspector = inspector;
		this.visionPort = visionPort;
		this.clock = clock;
	}

	@Override
	public QualityResult evaluate(GenerationAttemptId attemptId, byte[] applicationOwnedImage) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		try {
			ImageInspector.Result inspected = inspector.inspect(applicationOwnedImage);
			QualityDecision decision = inspected.width() < MIN_PASS_DIMENSION
				|| inspected.height() < MIN_PASS_DIMENSION
				? QualityDecision.RETRY
				: QualityDecision.PASS;
			String reasonCode = decision == QualityDecision.RETRY ? IMAGE_TOO_SMALL : null;
			return result(attemptId, decision, null, null, null, null, null,
				BASELINE_POLICY_VERSION, reasonCode);
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			return result(attemptId, QualityDecision.FAIL, null, null, null, null, null,
				BASELINE_POLICY_VERSION, IMAGE_INVALID);
		}
		catch (RuntimeException ex) {
			/*
			 * The candidate-only API has no Unavailable outcome. Keep its
			 * historical fail-closed shape while the request API can represent
			 * evaluator infrastructure unavailability explicitly.
			 */
			return result(attemptId, QualityDecision.FAIL, null, null, null, null, null,
				BASELINE_POLICY_VERSION, IMAGE_INVALID);
		}
	}

	@Override
	public QualityEvaluationOutcome evaluate(QualityEvaluationRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request is required");
		}
		String bound = QualityGate.boundPolicyVersion(request.boundPolicyVersion());
		if (!BASELINE_POLICY_VERSION.equals(bound) && !QUALITY_V2_POLICY_VERSION.equals(bound)) {
			return unavailable(QUALITY_POLICY_UNSUPPORTED);
		}
		if (BASELINE_POLICY_VERSION.equals(bound)) {
			return new QualityEvaluationOutcome.Completed(evaluate(request.attemptId(), request.candidateBytes()));
		}
		if (request.subjectBytes() == null || request.subjectBytes().length == 0
			|| request.garmentBytes() == null || request.garmentBytes().length == 0) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.FAIL,
				null,
				null,
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				INPUT_MEDIA_MISSING));
		}

		ImageInspector.Result inspected;
		try {
			inspected = inspector.inspect(request.candidateBytes());
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.FAIL,
				null,
				null,
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				IMAGE_INVALID));
		}
		catch (RuntimeException | LinkageError ex) {
			return unavailable(EVALUATOR_UNAVAILABLE);
		}
		if (inspected.width() < MIN_PASS_DIMENSION || inspected.height() < MIN_PASS_DIMENSION) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				null,
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				IMAGE_TOO_SMALL));
		}
		if (visionPort == null) {
			return unavailable(EVALUATOR_UNAVAILABLE);
		}

		QualityVisionEvidence evidence;
		try {
			evidence = visionPort.inspect(
				request.candidateBytes(),
				request.subjectBytes(),
				request.garmentBytes(),
				request.previousAcceptedBytes());
			if (!validEvidence(evidence)) {
				return unavailable(EVALUATOR_UNAVAILABLE);
			}
		}
		catch (RuntimeException | LinkageError ex) {
			return unavailable(EVALUATOR_UNAVAILABLE);
		}

		if (evidence.personCount() != 1) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				null,
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				"PERSON_NOT_PRESERVED"));
		}
		if (!evidence.bodySufficientlyVisible()) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				null,
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				"BODY_NOT_PRESERVED"));
		}
		if (evidence.garmentFidelity().compareTo(MIN_GARMENT_FIDELITY) < 0) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				evidence.garmentFidelity(),
				null,
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				"GARMENT_FIDELITY_LOW"));
		}
		if (evidence.colorFidelity().compareTo(MIN_COLOR_FIDELITY) < 0) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				evidence.garmentFidelity(),
				evidence.colorFidelity(),
				null,
				null,
				QUALITY_V2_POLICY_VERSION,
				"COLOR_FIDELITY_LOW"));
		}
		if (!evidence.anatomyValid()) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				null,
				evidence.garmentFidelity(),
				evidence.colorFidelity(),
				false,
				null,
				QUALITY_V2_POLICY_VERSION,
				"ANATOMY_INVALID"));
		}
		if (evidence.identityPreservation().compareTo(MIN_IDENTITY_PRESERVATION) < 0) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				evidence.identityPreservation(),
				evidence.garmentFidelity(),
				evidence.colorFidelity(),
				true,
				null,
				QUALITY_V2_POLICY_VERSION,
				"IDENTITY_NOT_PRESERVED"));
		}
		if (request.previousAcceptedBytes() != null && request.previousAcceptedBytes().length > 0
			&& !evidence.previousItemsPreserved()) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				evidence.identityPreservation(),
				evidence.garmentFidelity(),
				evidence.colorFidelity(),
				true,
				null,
				QUALITY_V2_POLICY_VERSION,
				"PREVIOUS_ITEMS_NOT_PRESERVED"));
		}
		if (evidence.artifactDetected()) {
			return new QualityEvaluationOutcome.Completed(result(
				request.attemptId(),
				QualityDecision.RETRY,
				evidence.identityPreservation(),
				evidence.garmentFidelity(),
				evidence.colorFidelity(),
				true,
				true,
				QUALITY_V2_POLICY_VERSION,
				"ARTIFACT_DETECTED"));
		}
		return new QualityEvaluationOutcome.Completed(result(
			request.attemptId(),
			QualityDecision.PASS,
			evidence.identityPreservation(),
			evidence.garmentFidelity(),
			evidence.colorFidelity(),
			true,
			false,
			QUALITY_V2_POLICY_VERSION,
			null));
	}

	private static boolean validEvidence(QualityVisionEvidence evidence) {
		return evidence != null
			&& inRange(evidence.identityPreservation())
			&& inRange(evidence.garmentFidelity())
			&& inRange(evidence.colorFidelity())
			&& evidence.personCount() >= 0;
	}

	private static boolean inRange(BigDecimal score) {
		return score != null
			&& score.compareTo(BigDecimal.ZERO) >= 0
			&& score.compareTo(BigDecimal.ONE) <= 0;
	}

	private QualityResult result(
		GenerationAttemptId attemptId,
		QualityDecision decision,
		BigDecimal identityPreservation,
		BigDecimal garmentFidelity,
		BigDecimal colorFidelity,
		Boolean anatomyValid,
		Boolean artifactDetected,
		String qualityPolicyVersion,
		String reasonCode) {
		return new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			attemptId,
			decision,
			identityPreservation,
			garmentFidelity,
			colorFidelity,
			anatomyValid,
			artifactDetected,
			clock.instant(),
			qualityPolicyVersion,
			reasonCode);
	}

	private static QualityEvaluationOutcome.Unavailable unavailable(String reasonCode) {
		return new QualityEvaluationOutcome.Unavailable(reasonCode);
	}
}
