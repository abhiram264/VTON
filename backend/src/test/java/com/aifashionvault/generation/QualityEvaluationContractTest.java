package com.aifashionvault.generation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.aifashionvault.platform.generation.ApplicationOwnedQualityGate;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QualityEvaluationContractTest {

	private static final GenerationAttemptId ATTEMPT_ID = new GenerationAttemptId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

	private final QualityGate baselineGate = new ApplicationOwnedQualityGate(new ImageInspector());

	@Test
	void nullRequestIsRejected() {
		assertThatThrownBy(() -> baselineGate.evaluate((QualityEvaluationRequest) null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownBoundPolicyVersionIsUnavailable() {
		QualityEvaluationOutcome outcome = baselineGate.evaluate(request(null, "quality-v9"));

		assertThat(outcome).isInstanceOf(QualityEvaluationOutcome.Unavailable.class);
		assertThat(((QualityEvaluationOutcome.Unavailable) outcome).reasonCode())
			.isEqualTo("QUALITY_POLICY_UNSUPPORTED");
	}

	@Test
	void baselineDispatchMatchesCandidateOnlyEvaluation() throws IOException {
		assertBaselineDecision(raster("PNG", 256, 256), QualityDecision.PASS, null);
		assertBaselineDecision(raster("PNG", 16, 20), QualityDecision.RETRY, "IMAGE_TOO_SMALL");
		assertBaselineDecision(
			"not-an-image".getBytes(StandardCharsets.US_ASCII),
			QualityDecision.FAIL,
			"IMAGE_INVALID");
	}

	@Test
	void qualityV2ThroughPortDefaultIsUnavailable() throws IOException {
		QualityGate portDefault = new QualityGate() {
			@Override
			public QualityResult evaluate(GenerationAttemptId attemptId, byte[] candidateBytes) {
				return baselineGate.evaluate(attemptId, candidateBytes);
			}
		};
		QualityEvaluationOutcome outcome = portDefault.evaluate(
			request(raster("PNG", 256, 256), QualityGate.QUALITY_V2_POLICY_VERSION));

		assertThat(outcome).isInstanceOf(QualityEvaluationOutcome.Unavailable.class);
		assertThat(((QualityEvaluationOutcome.Unavailable) outcome).reasonCode())
			.isEqualTo("QUALITY_POLICY_UNSUPPORTED");
	}

	@Test
	void outcomesRejectMissingValues() {
		assertThatThrownBy(() -> new QualityEvaluationOutcome.Completed(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityEvaluationOutcome.Unavailable(null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new QualityEvaluationOutcome.Unavailable("  \t"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertBaselineDecision(byte[] candidate, QualityDecision decision, String reasonCode) {
		QualityResult direct = baselineGate.evaluate(ATTEMPT_ID, candidate);
		QualityEvaluationOutcome outcome = baselineGate.evaluate(request(candidate, QualityGate.BASELINE_POLICY_VERSION));

		assertThat(outcome).isInstanceOf(QualityEvaluationOutcome.Completed.class);
		QualityResult dispatched = ((QualityEvaluationOutcome.Completed) outcome).result();
		assertThat(dispatched.decision()).isEqualTo(direct.decision()).isEqualTo(decision);
		assertThat(dispatched.reasonCode()).isEqualTo(reasonCode);
		assertThat(dispatched.qualityPolicyVersion()).isEqualTo(QualityGate.BASELINE_POLICY_VERSION);
		assertThat(dispatched.identityPreservation()).isNull();
		assertThat(dispatched.garmentFidelity()).isNull();
		assertThat(dispatched.colorFidelity()).isNull();
		assertThat(dispatched.anatomyValid()).isNull();
		assertThat(dispatched.artifactDetected()).isNull();
	}

	private static QualityEvaluationRequest request(byte[] candidate, String boundPolicyVersion) {
		return new QualityEvaluationRequest(
			ATTEMPT_ID,
			candidate,
			null,
			null,
			null,
			boundPolicyVersion,
			null);
	}

	private static byte[] raster(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, format, output)).isTrue();
		return output.toByteArray();
	}
}
