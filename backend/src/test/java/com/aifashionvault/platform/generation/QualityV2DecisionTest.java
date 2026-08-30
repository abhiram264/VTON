package com.aifashionvault.platform.generation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityEvaluationRequest.RenderStepContext;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityV2DecisionTest {

	private static final GenerationAttemptId ATTEMPT_ID = new GenerationAttemptId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final byte[] CANDIDATE = raster("PNG", 256, 256);
	private static final byte[] SUBJECT = raster("PNG", 256, 256);
	private static final byte[] GARMENT = raster("PNG", 256, 256);

	@Test
	void passPopulatesEveryQualityV2MeasurementAndUsesInjectedClock() {
		QualityEvaluationOutcome outcome = evaluate(evidence(1, true, "0.91", "0.88", "0.90", true, false, true), null);

		QualityResult result = completed(outcome);
		assertThat(result.decision()).isEqualTo(QualityDecision.PASS);
		assertThat(result.qualityPolicyVersion()).isEqualTo("quality-v2");
		assertThat(result.reasonCode()).isNull();
		assertThat(result.identityPreservation()).isEqualByComparingTo("0.91");
		assertThat(result.garmentFidelity()).isEqualByComparingTo("0.88");
		assertThat(result.colorFidelity()).isEqualByComparingTo("0.90");
		assertThat(result.anatomyValid()).isTrue();
		assertThat(result.artifactDetected()).isFalse();
		assertThat(result.createdAt()).isEqualTo(NOW);
	}

	@Test
	void missingSubjectOrGarmentFailsBeforeCallingVisionPort() {
		AtomicInteger calls = new AtomicInteger();
		QualityVisionPort port = (candidate, subject, garment, previous) -> {
			calls.incrementAndGet();
			return evidence(1, true, "0.91", "0.88", "0.90", true, false, true);
		};
		ApplicationOwnedQualityGate gate = new ApplicationOwnedQualityGate(new ImageInspector(), port, CLOCK);

		QualityResult missingSubject = completed(gate.evaluate(request(CANDIDATE, null, GARMENT, null)));
		QualityResult missingGarment = completed(gate.evaluate(request(CANDIDATE, SUBJECT, null, null)));

		assertThat(missingSubject.decision()).isEqualTo(QualityDecision.FAIL);
		assertThat(missingSubject.reasonCode()).isEqualTo("INPUT_MEDIA_MISSING");
		assertThat(missingGarment.decision()).isEqualTo(QualityDecision.FAIL);
		assertThat(missingGarment.reasonCode()).isEqualTo("INPUT_MEDIA_MISSING");
		assertThat(calls).hasValue(0);
	}

	@Test
	void emptyReferencesAlsoFailAsMissingMedia() {
		QualityResult missingSubject = completed(evaluate(
			evidence(1, true, "0.91", "0.88", "0.90", true, false, true), null,
			new byte[0], GARMENT));
		QualityResult missingGarment = completed(evaluate(
			evidence(1, true, "0.91", "0.88", "0.90", true, false, true), null,
			SUBJECT, new byte[0]));

		assertThat(missingSubject.reasonCode()).isEqualTo("INPUT_MEDIA_MISSING");
		assertThat(missingGarment.reasonCode()).isEqualTo("INPUT_MEDIA_MISSING");
	}

	@Test
	void invalidOrEmptyCandidateFailsBeforeVisionPort() {
		AtomicInteger calls = new AtomicInteger();
		QualityVisionPort port = (candidate, subject, garment, previous) -> {
			calls.incrementAndGet();
			return evidence(1, true, "0.91", "0.88", "0.90", true, false, true);
		};
		ApplicationOwnedQualityGate gate = new ApplicationOwnedQualityGate(new ImageInspector(), port, CLOCK);

		QualityResult empty = completed(gate.evaluate(request(new byte[0], SUBJECT, GARMENT, null)));
		QualityResult nullCandidate = completed(gate.evaluate(request(null, SUBJECT, GARMENT, null)));
		QualityResult garbage = completed(gate.evaluate(request(new byte[] {1, 2, 3}, SUBJECT, GARMENT, null)));

		assertThat(empty.reasonCode()).isEqualTo("IMAGE_INVALID");
		assertThat(nullCandidate.reasonCode()).isEqualTo("IMAGE_INVALID");
		assertThat(garbage.reasonCode()).isEqualTo("IMAGE_INVALID");
		assertThat(calls).hasValue(0);
	}

	@Test
	void tooSmallCandidateRetriesForQualityV2() {
		QualityResult result = completed(new ApplicationOwnedQualityGate(new ImageInspector(),
			(candidate, subject, garment, previous) -> evidence(1, true, "0.91", "0.88", "0.90", true, false, true),
			CLOCK).evaluate(request(raster("PNG", 128, 256), SUBJECT, GARMENT, null)));

		assertThat(result.decision()).isEqualTo(QualityDecision.RETRY);
		assertThat(result.reasonCode()).isEqualTo("IMAGE_TOO_SMALL");
		assertThat(result.identityPreservation()).isNull();
	}

	@Test
	void personAndBodyChecksShortCircuitInOrder() {
		QualityResult zero = completed(evaluate(evidence(0, true, "0.91", "0.88", "0.90", true, false, true), null));
		QualityResult multiple = completed(evaluate(evidence(2, true, "0.91", "0.88", "0.90", true, false, true), null));
		QualityResult body = completed(evaluate(evidence(1, false, "0.91", "0.88", "0.90", true, false, true), null));

		assertRetry(zero, "PERSON_NOT_PRESERVED");
		assertRetry(multiple, "PERSON_NOT_PRESERVED");
		assertRetry(body, "BODY_NOT_PRESERVED");
		assertThat(body.garmentFidelity()).isNull();
	}

	@Test
	void qualityAxesUseFrozenThresholdsAndShortCircuit() {
		assertRetry(completed(evaluate(evidence(1, true, "0.91", "0.79", "0.90", true, false, true), null)),
			"GARMENT_FIDELITY_LOW");
		assertRetry(completed(evaluate(evidence(1, true, "0.91", "0.80", "0.84", true, false, true), null)),
			"COLOR_FIDELITY_LOW");
		assertRetry(completed(evaluate(evidence(1, true, "0.91", "0.80", "0.85", false, false, true), null)),
			"ANATOMY_INVALID");
		assertRetry(completed(evaluate(evidence(1, true, "0.79", "0.80", "0.85", true, false, true), null)),
			"IDENTITY_NOT_PRESERVED");
	}

	@Test
	void previousItemAndArtifactChecksComeAfterIdentity() {
		QualityResult previous = completed(evaluate(
			evidence(1, true, "0.91", "0.88", "0.90", true, false, false),
			CANDIDATE, SUBJECT, GARMENT));
		QualityResult artifact = completed(evaluate(
			evidence(1, true, "0.91", "0.88", "0.90", true, true, true), null));

		assertRetry(previous, "PREVIOUS_ITEMS_NOT_PRESERVED");
		assertThat(previous.identityPreservation()).isEqualByComparingTo("0.91");
		assertThat(previous.artifactDetected()).isNull();
		assertRetry(artifact, "ARTIFACT_DETECTED");
		assertThat(artifact.artifactDetected()).isTrue();
	}

	@Test
	void unknownPolicyAndUnavailablePortFailClosedWithoutCompletedResult() {
		QualityEvaluationOutcome unknown = new ApplicationOwnedQualityGate(new ImageInspector(),
			(candidate, subject, garment, previous) -> evidence(1, true, "0.91", "0.88", "0.90", true, false, true), CLOCK)
			.evaluate(request(CANDIDATE, SUBJECT, GARMENT, null, "quality-v9"));
		QualityEvaluationOutcome unavailable = new ApplicationOwnedQualityGate(new ImageInspector(),
			(candidate, subject, garment, previous) -> {
				throw new IllegalStateException("https://private.example/tensor=[secret]");
			}, CLOCK).evaluate(request(CANDIDATE, SUBJECT, GARMENT, null));
		QualityEvaluationOutcome noPort = new ApplicationOwnedQualityGate(new ImageInspector(), null, CLOCK)
			.evaluate(request(CANDIDATE, SUBJECT, GARMENT, null));

		assertUnavailable(unknown, "QUALITY_POLICY_UNSUPPORTED");
		assertUnavailable(unavailable, "EVALUATOR_UNAVAILABLE");
		assertUnavailable(noPort, "EVALUATOR_UNAVAILABLE");
	}

	@Test
	void outOfRangeEvidenceIsUnavailableInsteadOfClamped() {
		QualityVisionEvidence malformed = mock(QualityVisionEvidence.class);
		when(malformed.personCount()).thenReturn(1);
		when(malformed.bodySufficientlyVisible()).thenReturn(true);
		when(malformed.identityPreservation()).thenReturn(new BigDecimal("1.7"));
		when(malformed.garmentFidelity()).thenReturn(new BigDecimal("0.90"));
		when(malformed.colorFidelity()).thenReturn(new BigDecimal("0.90"));
		when(malformed.anatomyValid()).thenReturn(true);
		when(malformed.artifactDetected()).thenReturn(false);
		when(malformed.previousItemsPreserved()).thenReturn(true);

		QualityEvaluationOutcome outcome = evaluate(() -> malformed, null);
		assertUnavailable(outcome, "EVALUATOR_UNAVAILABLE");
	}

	private QualityEvaluationOutcome evaluate(QualityVisionEvidence evidence, byte[] previous) {
		return evaluate(() -> evidence, previous, SUBJECT, GARMENT);
	}

	private QualityEvaluationOutcome evaluate(QualityVisionEvidence evidence, byte[] previous,
		byte[] subject, byte[] garment) {
		return evaluate(() -> evidence, previous, subject, garment);
	}

	private QualityEvaluationOutcome evaluate(QualityVisionPort port, byte[] previous) {
		return new ApplicationOwnedQualityGate(new ImageInspector(), port, CLOCK)
			.evaluate(request(CANDIDATE, SUBJECT, GARMENT, previous));
	}

	private QualityEvaluationOutcome evaluate(PortSupplier supplier, byte[] previous) {
		return evaluate((candidate, subject, garment, previousAccepted) -> supplier.get(), previous);
	}

	private QualityEvaluationOutcome evaluate(PortSupplier supplier, byte[] previous,
		byte[] subject, byte[] garment) {
		return new ApplicationOwnedQualityGate(new ImageInspector(),
			(candidate, actualSubject, actualGarment, previousAccepted) -> supplier.get(), CLOCK)
			.evaluate(request(CANDIDATE, subject, garment, previous));
	}

	private static QualityEvaluationRequest request(byte[] candidate, byte[] subject, byte[] garment, byte[] previous) {
		return request(candidate, subject, garment, previous, "quality-v2");
	}

	private static QualityEvaluationRequest request(byte[] candidate, byte[] subject, byte[] garment,
		byte[] previous, String policy) {
		return new QualityEvaluationRequest(ATTEMPT_ID, candidate, subject, garment, previous, policy,
			new RenderStepContext(1, 1, true));
	}

	private static QualityVisionEvidence evidence(int people, boolean body, String identity, String garment,
		String color, boolean anatomy, boolean artifact, boolean previous) {
		return new QualityVisionEvidence(people, body, new BigDecimal(identity), new BigDecimal(garment),
			new BigDecimal(color), anatomy, artifact, previous);
	}

	private static QualityResult completed(QualityEvaluationOutcome outcome) {
		assertThat(outcome).isInstanceOf(QualityEvaluationOutcome.Completed.class);
		return ((QualityEvaluationOutcome.Completed) outcome).result();
	}

	private static void assertRetry(QualityResult result, String reason) {
		assertThat(result.decision()).isEqualTo(QualityDecision.RETRY);
		assertThat(result.reasonCode()).isEqualTo(reason);
	}

	private static void assertUnavailable(QualityEvaluationOutcome outcome, String reason) {
		assertThat(outcome).isInstanceOf(QualityEvaluationOutcome.Unavailable.class);
		assertThat(((QualityEvaluationOutcome.Unavailable) outcome).reasonCode()).isEqualTo(reason);
	}

	@FunctionalInterface
	private interface PortSupplier {
		QualityVisionEvidence get();
	}

	private static byte[] raster(String format, int width, int height) {
		try {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = image.createGraphics();
			try {
				graphics.setColor(Color.BLUE);
				graphics.fillRect(0, 0, width, height);
			}
			finally {
				graphics.dispose();
			}
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			assertThat(ImageIO.write(image, format, output)).isTrue();
			return output.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
