package com.aifashionvault.platform.generation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityEvaluationRequest.RenderStepContext;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationOwnedQualityGateTest {

	private static final GenerationAttemptId ATTEMPT_ID = new GenerationAttemptId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final ApplicationOwnedQualityGate gate = new ApplicationOwnedQualityGate(new ImageInspector(), CLOCK);

	@Test
	void implementsQualityGateEvaluateContract() throws Exception {
		assertThat(QualityGate.class).isInterface();
		Method evaluate = QualityGate.class.getMethod("evaluate", GenerationAttemptId.class, byte[].class);
		assertThat(evaluate.getReturnType()).isEqualTo(QualityResult.class);
		assertThat(ApplicationOwnedQualityGate.class).isAssignableTo(QualityGate.class);
	}

	@Test
	void recordsBaselineV1ForPassRetryAndFail() throws Exception {
		assertThat(gate.policyVersion()).isEqualTo("baseline-v1");
		assertThat(QualityGate.BASELINE_POLICY_VERSION).isEqualTo("baseline-v1");

		QualityResult pass = gate.evaluate(ATTEMPT_ID, raster("PNG", 256, 256));
		QualityResult retry = gate.evaluate(ATTEMPT_ID, raster("PNG", 16, 20));
		QualityResult fail = gate.evaluate(ATTEMPT_ID, "not-an-image".getBytes(StandardCharsets.US_ASCII));

		assertThat(pass.decision()).isEqualTo(QualityDecision.PASS);
		assertThat(retry.decision()).isEqualTo(QualityDecision.RETRY);
		assertThat(fail.decision()).isEqualTo(QualityDecision.FAIL);
		assertThat(pass.qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(retry.qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(fail.qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(pass.reasonCode()).isNull();
		assertThat(retry.reasonCode()).isEqualTo("IMAGE_TOO_SMALL");
		assertThat(fail.reasonCode()).isEqualTo("IMAGE_INVALID");
	}

	@Test
	void redeliveryReusesBoundBaselinePolicyAndRefusesSilentReinterpretation() throws Exception {
		byte[] image = raster("PNG", 256, 256);

		QualityResult first = gate.evaluate(ATTEMPT_ID, image);
		QualityResult redelivered = gate.evaluate(ATTEMPT_ID, image, first.qualityPolicyVersion());

		assertThat(first.bindPolicyVersion("baseline-v1").qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(redelivered.decision()).isEqualTo(QualityDecision.PASS);
		assertThat(redelivered.qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThat(gate.evaluate(ATTEMPT_ID, image, null).qualityPolicyVersion()).isEqualTo("baseline-v1");
		assertThatThrownBy(() -> gate.evaluate(ATTEMPT_ID, image, "future-v2"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("QualityPolicyVersion");
	}

	@Test
	void passForValidJpegAndPngAtLeast256() throws Exception {
		assertPass(raster("PNG", 256, 256));
		assertPass(raster("JPEG", 256, 300));
	}

	@Test
	void retryWhenInspectSucceedsButWidthOrHeightIsBelow256() throws Exception {
		assertRetry(raster("PNG", 16, 20));
		assertRetry(raster("JPEG", 256, 15));
		assertRetry(raster("PNG", 15, 256));
	}

	@Test
	void failOnUnknownMagicAndImageValidationException() throws Exception {
		assertFail("not-an-image".getBytes(StandardCharsets.US_ASCII));
		assertFail(new byte[0]);
		assertFail(null);
		assertFail(raster("PNG", 1, 1));
		assertFail(raster("JPEG", 1, 1));
	}

	@Test
	void requestPathReportsQualityV2EvaluatorUnavailableWithoutInfrastructureFlag() throws Exception {
		ApplicationOwnedQualityGate unavailable = new ApplicationOwnedQualityGate(
			new ImageInspector(),
			(candidate, subject, garment, previousAccepted) -> {
				throw new IllegalStateException("https://private.example/tensor=[secret]");
			},
			CLOCK);
		QualityEvaluationRequest request = new QualityEvaluationRequest(
			ATTEMPT_ID,
			raster("PNG", 256, 256),
			raster("PNG", 256, 256),
			raster("PNG", 256, 256),
			null,
			QualityGate.QUALITY_V2_POLICY_VERSION,
			new RenderStepContext(1, 1, true));

		QualityEvaluationOutcome outcome = unavailable.evaluate(request);

		assertThat(((QualityEvaluationOutcome.Unavailable) outcome).reasonCode())
			.isEqualTo("EVALUATOR_UNAVAILABLE");
	}


	@Test
	void numericScoresRemainNullAndCreatedAtIsNow() throws Exception {
		QualityResult result = gate.evaluate(ATTEMPT_ID, raster("PNG", 256, 256));

		assertThat(result.generationAttemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(result.id()).isNotNull();
		assertThat(result.id().value()).isNotNull();
		assertThat(result.identityPreservation()).isNull();
		assertThat(result.garmentFidelity()).isNull();
		assertThat(result.colorFidelity()).isNull();
		assertThat(result.anatomyValid()).isNull();
		assertThat(result.artifactDetected()).isNull();
		assertThat(result.createdAt()).isEqualTo(NOW);
	}

	@Test
	void eachEvaluationGetsANewResultId() throws Exception {
		byte[] png = raster("PNG", 256, 256);

		QualityResult first = gate.evaluate(ATTEMPT_ID, png);
		QualityResult second = gate.evaluate(ATTEMPT_ID, png);

		assertThat(first.id()).isNotEqualTo(second.id());
	}

	@Test
	void rejectsNullInspectorAndAttemptId() {
		assertThatThrownBy(() -> new ApplicationOwnedQualityGate(null, CLOCK))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ApplicationOwnedQualityGate(new ImageInspector(), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> gate.evaluate(null, raster("PNG", 256, 256)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sourceDoesNotOpenProviderMediaOrLogBytes() throws Exception {
		String gateSource = Files.readString(productionFile(
			"platform", "generation", "ApplicationOwnedQualityGate.java"));
		String contractSource = Files.readString(productionFile(
			"generation", "QualityGate.java"));
		String portSource = Files.readString(productionFile(
			"platform", "generation", "QualityVisionPort.java"));
		String onnxSource = Files.readString(productionFile(
			"platform", "generation", "OnnxCpuQualityVision.java"));

		assertThat(gateSource)
			.contains("ImageInspector")
			.doesNotContain("VtonProvider")
			.doesNotContain("Fashn")
			.doesNotContain("java.net.URL")
			.doesNotContain("java.net.URI")
			.doesNotContain("HttpClient")
			.doesNotContain("openStream")
			.doesNotContain("Logger")
			.doesNotContain("System.out")
			.doesNotContain("log(");
		assertThat(portSource)
			.doesNotContain("VtonProvider")
			.doesNotContain("Fashn")
			.doesNotContain("java.net.URL")
			.doesNotContain("java.net.URI")
			.doesNotContain("HttpClient");
		assertThat(onnxSource)
			.doesNotContain("VtonProvider")
			.doesNotContain("Fashn")
			.doesNotContain("java.net.URL")
			.doesNotContain("java.net.URI")
			.doesNotContain("HttpClient")
			.doesNotContain("com.aifashionvault.avatar.");
		assertThat(contractSource)
			.doesNotContain("VtonProvider")
			.doesNotContain("Fashn")
			.doesNotContain("java.net.URL");
	}

	@Test
	void createdAtUsesClockNowWhenSystemClockIsUsed() throws Exception {
		ApplicationOwnedQualityGate live = new ApplicationOwnedQualityGate(new ImageInspector());
		Instant before = Instant.now().minus(Duration.ofSeconds(2));

		QualityResult result = live.evaluate(ATTEMPT_ID, raster("PNG", 256, 256));

		Instant after = Instant.now().plus(Duration.ofSeconds(2));
		assertThat(result.createdAt()).isBetween(before, after);
		assertThat(result.decision()).isEqualTo(QualityDecision.PASS);
	}

	private void assertPass(byte[] image) {
		QualityResult result = gate.evaluate(ATTEMPT_ID, image);
		assertThat(result.decision()).isEqualTo(QualityDecision.PASS);
		assertThat(result.generationAttemptId()).isEqualTo(ATTEMPT_ID);
	}

	private void assertRetry(byte[] image) {
		QualityResult result = gate.evaluate(ATTEMPT_ID, image);
		assertThat(result.decision()).isEqualTo(QualityDecision.RETRY);
	}

	private void assertFail(byte[] image) {
		QualityResult result = gate.evaluate(ATTEMPT_ID, image);
		assertThat(result.decision()).isEqualTo(QualityDecision.FAIL);
	}

	private static byte[] raster(String format, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
		if (!writers.hasNext()) {
			throw new IllegalStateException("no ImageWriter for " + format);
		}
		ImageWriter writer = writers.next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(output);
			writer.write(image);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static Path productionFile(String... parts) {
		Path relative = Path.of("src", "main", "java", "com", "aifashionvault");
		for (String part : parts) {
			relative = relative.resolve(part);
		}
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path direct = cwd.resolve(relative);
		if (Files.isRegularFile(direct)) {
			return direct;
		}
		Path underBackend = cwd.resolve("backend").resolve(relative);
		if (Files.isRegularFile(underBackend)) {
			return underBackend;
		}
		throw new IllegalStateException("Cannot locate " + relative + " from " + cwd);
	}
}
